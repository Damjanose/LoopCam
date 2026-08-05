const fs = require('fs');
const path = require('path');

const { withDangerousMod } = require('expo/config-plugins');

/**
 * Builds the `ExpoModulesCore` pod in Swift 5 language mode.
 *
 * `expo-modules-core@57.0.9` ships `s.swift_version = '6.0'` in its podspec, so
 * its own sources are compiled with Swift 6 data-race checking — and under
 * Xcode 26 two of them do not survive it:
 *
 *   ios/Core/Events/EventEmitter.swift:52  sending 'emitter' risks causing data races
 *   ios/Core/Events/EventEmitter.swift:79  sending 'emitter' risks causing data races
 *
 * In Swift 6 language mode those are errors and the build stops. Nothing in
 * this project can fix them — they are upstream — so the pod is dropped to
 * Swift 5 + minimal concurrency checking, which turns them back into warnings.
 *
 * Scoped to that one pod target on purpose: every other pod, and all of our own
 * code, keeps whatever checking it asked for.
 *
 * Remove this once expo-modules-core compiles cleanly under Swift 6; it is
 * needed only while we build React Native from source (`buildReactNativeFromSource`
 * in app.json), because the precompiled ExpoModulesCore binary is not built here.
 */
const MARKER = '# loopcam: ExpoModulesCore Swift 5 language mode';

const SNIPPET = `
    ${MARKER}
    installer.pods_project.targets.each do |pod_target|
      next unless pod_target.name == 'ExpoModulesCore'
      pod_target.build_configurations.each do |config|
        config.build_settings['SWIFT_VERSION'] = '5.0'
        config.build_settings['SWIFT_STRICT_CONCURRENCY'] = 'minimal'
      end
    end
`;

module.exports = function withExpoModulesCoreSwift5(config) {
  return withDangerousMod(config, [
    'ios',
    (mod) => {
      const podfile = path.join(mod.modRequest.platformProjectRoot, 'Podfile');
      const contents = fs.readFileSync(podfile, 'utf8');

      if (contents.includes(MARKER)) {
        return mod;
      }

      // Append inside the existing `post_install do |installer|` block, after
      // react_native_post_install has finished rewriting the pod targets.
      // The call spans several lines and its arguments contain parentheses of
      // their own (`ccache_enabled?(podfile_properties)`), so match through to
      // the closing paren sitting on its own line.
      const anchor = /( *react_native_post_install\([\s\S]*?\n *\)\n)/;
      if (!anchor.test(contents)) {
        throw new Error(
          'withExpoModulesCoreSwift5: could not find react_native_post_install in the Podfile'
        );
      }

      fs.writeFileSync(podfile, contents.replace(anchor, `$1${SNIPPET}`), 'utf8');
      return mod;
    },
  ]);
};
