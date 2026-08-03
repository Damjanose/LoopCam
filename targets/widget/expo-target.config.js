/**
 * The Lock Screen Live Activity extension.
 *
 * `ios/` is prebuild output and is not committed, so the Xcode target has to be
 * generated: `@bacons/apple-targets` links this whole folder — `Shared/`
 * included — into an extension target on every `npx expo prebuild`.
 *
 * 17.0 because `Button(intent:)` inside a Live Activity is iOS 17. The app
 * itself stays on 16.4; the Live Activity code there is `@available`-guarded so
 * no iOS 16 users are dropped for a Lock Screen convenience.
 *
 * @type {import('@bacons/apple-targets/app.plugin').Config}
 */
module.exports = {
  type: 'widget',
  name: 'LoopCamWidget',
  displayName: 'LoopCam',
  deploymentTarget: '17.0',
  frameworks: ['SwiftUI', 'WidgetKit', 'ActivityKit', 'AppIntents'],
  colors: {
    $accent: '#4CC2FF',
    $widgetBackground: '#0A0A0F',
  },
};
