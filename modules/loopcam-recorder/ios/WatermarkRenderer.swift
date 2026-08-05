import CoreImage
import Foundation
import UIKit

/// Draws what every recorded frame carries on top of the picture: the burned-in
/// wall clock, and — in `both` mode — the front camera's inset beside it.
///
/// Burned in at *capture*, not at merge: Save is a stream-copy concat, and
/// drawing text at merge time would mean decoding, compositing and re-encoding
/// the whole window on every Save — the loss of the single biggest battery
/// lever in §6. One composite per frame is far the cheaper end of that trade.
///
/// The text itself is rasterised at most once per second and reused for the ~29
/// frames in between; only the composite is per-frame.
final class WatermarkRenderer {
  /// One Metal-backed context for the process. Creating a `CIContext` is
  /// expensive enough that doing it per clip would show up at every boundary.
  static let shared = WatermarkRenderer()

  let context: CIContext

  private let formatter: DateFormatter
  private var cachedKey: String?
  private var cachedImage: CIImage?
  private var cachedChromeKey: String?
  private var cachedChrome: (mask: CIImage, border: CIImage)?

  private init() {
    context = CIContext(options: [.useSoftwareRenderer: false])
    formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = WatermarkStyle.pattern
  }

  /// The overlay for `date`, already positioned in the pixel space of a buffer
  /// of `pixelSize`. Returns nil only if the plate could not be rasterised.
  ///
  /// Called on the capture queue and nowhere else, which is why the cache below
  /// needs no lock.
  func overlay(for date: Date, pixelSize: CGSize) -> CIImage? {
    let text = formatter.string(from: date)
    let key = "\(text)|\(Int(pixelSize.width))x\(Int(pixelSize.height))"
    if key == cachedKey, let cachedImage { return cachedImage }

    guard let image = makeOverlay(text: text, pixelSize: pixelSize) else { return nil }
    cachedKey = key
    cachedImage = image
    return image
  }

  private func makeOverlay(text: String, pixelSize: CGSize) -> CIImage? {
    // The pixels are landscape and `videoInput.transform` asks players to show
    // them portrait, so the frame as *displayed* is the buffer turned on its
    // side. Everything below is laid out in that displayed space and mapped
    // back at the end — text laid out in pixel space would come out rotated 90°
    // in every player, which is the whole trap this file exists to avoid.
    let displaySize = CGSize(width: pixelSize.height, height: pixelSize.width)

    let textHeight = displaySize.height * WatermarkStyle.textHeightFraction
    let font = UIFont.monospacedDigitSystemFont(ofSize: textHeight, weight: .medium)
    let attributes: [NSAttributedString.Key: Any] = [
      .font: font,
      .foregroundColor: UIColor.white,
    ]
    let textSize = (text as NSString).size(withAttributes: attributes)

    let padX = textHeight * WatermarkStyle.padXFraction
    let padY = textHeight * WatermarkStyle.padYFraction
    let plateSize = CGSize(
      width: (textSize.width + padX * 2).rounded(.up),
      height: (textSize.height + padY * 2).rounded(.up)
    )

    // Scale 1: the sizes above are already in frame pixels, and the renderer's
    // default screen scale would silently draw a 3× plate.
    let format = UIGraphicsImageRendererFormat(for: UITraitCollection(displayScale: 1))
    format.opaque = false
    let plate = UIGraphicsImageRenderer(size: plateSize, format: format).image { _ in
      let rect = CGRect(origin: .zero, size: plateSize)
      let corner = plateSize.height * WatermarkStyle.cornerFraction
      UIColor.black.withAlphaComponent(WatermarkStyle.plateAlpha).setFill()
      UIBezierPath(roundedRect: rect, cornerRadius: corner).fill()
      (text as NSString).draw(at: CGPoint(x: padX, y: padY), withAttributes: attributes)
    }
    guard let cgImage = plate.cgImage else { return nil }

    let inset = min(displaySize.width, displaySize.height) * WatermarkStyle.insetFraction
    // CoreImage's origin is bottom-left, so the displayed bottom edge is y = 0.
    let place = CGAffineTransform(
      translationX: displaySize.width - inset - plateSize.width,
      y: inset
    )
    return CIImage(cgImage: cgImage).transformed(by: place.concatenating(displayToPixel(pixelSize)))
  }

  // MARK: - the front-camera inset

  /// The front camera's frame, cropped, rounded and positioned in the top-right
  /// of the displayed frame, ready to be composited over the back camera's.
  ///
  /// Returns nil for a front image with no extent — a frame that arrived
  /// malformed is a reason to skip the inset, never to skip the road.
  ///
  /// Called on the capture queue and nowhere else, like `overlay(for:)`, which
  /// is why the chrome cache below needs no lock.
  func pip(for front: CIImage, pixelSize: CGSize) -> CIImage? {
    let displaySize = CGSize(width: pixelSize.height, height: pixelSize.width)
    let pipWidth = (displaySize.width * WatermarkStyle.Pip.widthFraction).rounded()
    let pipHeight = (pipWidth / WatermarkStyle.Pip.aspect).rounded()
    let pipSize = CGSize(width: pipWidth, height: pipHeight)

    // The front buffer is landscape, exactly like the back one, so it needs the
    // same rotation to stand upright in displayed space. Skipping this is what
    // would put a sideways cabin in the corner of an otherwise correct file.
    let upright = front.transformed(
      by: displayToPixel(front.extent.size).inverted()
    )
    guard upright.extent.width > 0, upright.extent.height > 0 else { return nil }

    // Centre-crop to fill: scale by the *larger* ratio so neither axis leaves a
    // gap, then take the middle.
    let scale = max(pipWidth / upright.extent.width, pipHeight / upright.extent.height)
    let scaled = upright.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
    let box = scaled.extent
    let cropped = scaled.cropped(
      to: CGRect(
        x: box.origin.x + (box.width - pipWidth) / 2,
        y: box.origin.y + (box.height - pipHeight) / 2,
        width: pipWidth,
        height: pipHeight
      )
    )
    // Back to the origin, so the mask and the border below can be built once per
    // size rather than per frame position.
    let atOrigin = cropped.transformed(
      by: CGAffineTransform(translationX: -cropped.extent.origin.x, y: -cropped.extent.origin.y)
    )

    guard let chrome = chrome(for: pipSize) else { return nil }
    let rounded = atOrigin.applyingFilter(
      "CIBlendWithAlphaMask",
      parameters: [
        kCIInputBackgroundImageKey: CIImage.empty(),
        kCIInputMaskImageKey: chrome.mask,
      ]
    )
    let framed = chrome.border.composited(over: rounded)

    let inset = min(displaySize.width, displaySize.height) * WatermarkStyle.insetFraction
    // CoreImage's origin is bottom-left, so the displayed *top* edge is the far
    // one — hence height minus, where the stamp's placement is a bare inset.
    let place = CGAffineTransform(
      translationX: displaySize.width - inset - pipWidth,
      y: displaySize.height - inset - pipHeight
    )
    return framed.transformed(by: place.concatenating(displayToPixel(pixelSize)))
  }

  /// The rounded-corner mask and the hairline border for a PiP of this size.
  ///
  /// Both are pure functions of the size, and the size only changes when the
  /// capture format does — so they are rasterised once and reused for every
  /// frame after, which is the difference between two UIKit renders per frame
  /// and two per session.
  private func chrome(for size: CGSize) -> (mask: CIImage, border: CIImage)? {
    let key = "\(Int(size.width))x\(Int(size.height))"
    if key == cachedChromeKey, let cachedChrome { return cachedChrome }

    let corner = size.height * WatermarkStyle.Pip.cornerFraction
    let rect = CGRect(origin: .zero, size: size)
    let format = UIGraphicsImageRendererFormat(for: UITraitCollection(displayScale: 1))
    format.opaque = false
    let renderer = UIGraphicsImageRenderer(size: size, format: format)

    let mask = renderer.image { _ in
      UIColor.white.setFill()
      UIBezierPath(roundedRect: rect, cornerRadius: corner).fill()
    }
    let borderWidth = min(size.width, size.height) * WatermarkStyle.Pip.borderWidthFraction
    let border = renderer.image { _ in
      UIColor.white.withAlphaComponent(WatermarkStyle.Pip.borderAlpha).setStroke()
      // Inset by half the line width: a stroke straddles its path, and the
      // outer half would be clipped away by the image's own bounds.
      let path = UIBezierPath(
        roundedRect: rect.insetBy(dx: borderWidth / 2, dy: borderWidth / 2),
        cornerRadius: corner
      )
      path.lineWidth = borderWidth
      path.stroke()
    }

    guard let maskCG = mask.cgImage, let borderCG = border.cgImage else { return nil }
    let chrome = (mask: CIImage(cgImage: maskCG), border: CIImage(cgImage: borderCG))
    cachedChromeKey = key
    cachedChrome = chrome
    return chrome
  }

  /// Displayed space → buffer space, for the 90° clockwise display rotation the
  /// writer stamps into the container. The displayed bottom-right corner lands
  /// in the buffer's *top*-right, upright once a player has rotated it back.
  ///
  /// This mapping is the single most likely thing on iOS to be wrong, and it is
  /// wrong in a way only a played-back file reveals — hence its own function.
  private func displayToPixel(_ pixelSize: CGSize) -> CGAffineTransform {
    CGAffineTransform(translationX: pixelSize.width, y: 0).rotated(by: .pi / 2)
  }
}
