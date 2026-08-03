import ActivityKit
import SwiftUI
import WidgetKit

/// The Lock Screen card and Dynamic Island for a running session.
///
/// Only ever built into the widget extension. The app target gets the types in
/// `Shared/` but never these views.
struct LoopCamLiveActivityWidget: Widget {
  var body: some WidgetConfiguration {
    ActivityConfiguration(for: LoopCamActivityAttributes.self) { context in
      LockScreenCard(state: context.state, startedAt: context.attributes.startedAt)
        .activityBackgroundTint(Palette.background)
        .activitySystemActionForegroundColor(.white)
    } dynamicIsland: { context in
      DynamicIsland {
        DynamicIslandExpandedRegion(.leading) {
          StatusDot(isBusy: context.state.isBusy)
            .padding(.leading, 4)
        }
        DynamicIslandExpandedRegion(.trailing) {
          Text(context.state.bufferedLabel)
            .font(.system(.title3, design: .rounded).monospacedDigit().weight(.semibold))
            .foregroundStyle(.white)
            .padding(.trailing, 4)
        }
        DynamicIslandExpandedRegion(.center) {
          Text(context.state.banner ?? "buffered")
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        DynamicIslandExpandedRegion(.bottom) {
          Controls(state: context.state)
            .padding(.top, 2)
        }
      } compactLeading: {
        StatusDot(isBusy: context.state.isBusy, compact: true)
      } compactTrailing: {
        Text(context.state.bufferedLabel)
          .font(.caption.monospacedDigit())
          .foregroundStyle(Palette.accent)
      } minimal: {
        StatusDot(isBusy: context.state.isBusy, compact: true)
      }
      .keylineTint(Palette.accent)
    }
  }
}

// MARK: - Lock Screen

private struct LockScreenCard: View {
  let state: LoopCamActivityAttributes.ContentState
  let startedAt: Date

  var body: some View {
    VStack(alignment: .leading, spacing: 12) {
      HStack(alignment: .firstTextBaseline, spacing: 8) {
        StatusDot(isBusy: state.isBusy)
        VStack(alignment: .leading, spacing: 1) {
          Text("LoopCam")
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.white)
          // The banner takes the subtitle when there is one — on a locked
          // screen it is the only feedback a tap gets, so it outranks the
          // session timer.
          Group {
            if let banner = state.banner {
              Text(banner)
            } else {
              Text(startedAt, style: .timer)
            }
          }
          .font(.caption.monospacedDigit())
          .foregroundStyle(.white.opacity(0.6))
        }
        Spacer(minLength: 8)
        VStack(alignment: .trailing, spacing: 1) {
          Text(state.bufferedLabel)
            .font(.system(.title2, design: .rounded).monospacedDigit().weight(.bold))
            .foregroundStyle(.white)
          Text("buffered")
            .font(.caption2)
            .foregroundStyle(.white.opacity(0.5))
        }
      }

      BufferMeter(fill: state.fill)

      Controls(state: state)
    }
    .padding(16)
  }
}

/// How full the ring buffer is (§2.4). Once it saturates the oldest clip is
/// evicted per new clip, so a full bar is the steady state, not a warning.
private struct BufferMeter: View {
  let fill: Double

  var body: some View {
    GeometryReader { geo in
      ZStack(alignment: .leading) {
        Capsule()
          .fill(.white.opacity(0.15))
        Capsule()
          .fill(Palette.accent)
          .frame(width: max(4, geo.size.width * fill))
      }
    }
    .frame(height: 5)
  }
}

private struct StatusDot: View {
  let isBusy: Bool
  var compact: Bool = false

  var body: some View {
    Circle()
      .fill(isBusy ? Palette.busy : Palette.recording)
      .frame(width: compact ? 8 : 10, height: compact ? 8 : 10)
  }
}

// MARK: - buttons

/// The whole point of the card. `Button(intent:)` hands the tap to the app's
/// process (see `LoopCamActivityIntents`), so these work with the phone locked.
private struct Controls: View {
  let state: LoopCamActivityAttributes.ContentState

  var body: some View {
    HStack(spacing: 8) {
      if #available(iOS 17.0, *) {
        Button(intent: LoopCamSaveIntent()) {
          Label("Save", systemImage: "square.and.arrow.down.fill")
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(ActionButton(tint: Palette.accent, prominent: true))

        Button(intent: LoopCamStopIntent()) {
          Label("Stop", systemImage: "stop.fill")
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(ActionButton(tint: Palette.stop, prominent: false))
      }
    }
    // Nothing to act on while a Save or Stop is already in flight, and a second
    // tap would only queue work the engine has to reject (§2.3).
    .disabled(state.isBusy)
    .opacity(state.isBusy ? 0.5 : 1)
  }
}

private struct ActionButton: ButtonStyle {
  let tint: Color
  let prominent: Bool

  func makeBody(configuration: Configuration) -> some View {
    configuration.label
      .font(.footnote.weight(.semibold))
      .labelStyle(.titleAndIcon)
      .foregroundStyle(prominent ? Palette.background : tint)
      .padding(.vertical, 9)
      .background(
        Capsule().fill(prominent ? AnyShapeStyle(tint) : AnyShapeStyle(tint.opacity(0.18)))
      )
      .opacity(configuration.isPressed ? 0.7 : 1)
  }
}

private enum Palette {
  static let background = Color(red: 0.04, green: 0.04, blue: 0.06)
  static let accent = Color(red: 0.30, green: 0.76, blue: 1.0)
  static let recording = Color(red: 1.0, green: 0.27, blue: 0.31)
  static let busy = Color(red: 1.0, green: 0.72, blue: 0.20)
  static let stop = Color(red: 1.0, green: 0.42, blue: 0.42)
}
