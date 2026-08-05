import CoreImage
import Foundation
import UIKit

/// Draws the burned-in wall clock that every recorded frame carries.
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
