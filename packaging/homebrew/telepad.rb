# Telepad for macOS, as a Homebrew cask. It belongs in a tap of its own, in a repository named homebrew-telepad
# (as Casks/telepad.rb), so that people can run:
#
#   brew install --cask omsingh02/telepad/telepad
#
# scripts/update-package-manifests.sh <tag> fills in the version and the checksum from a release.
cask "telepad" do
  version "2.0.0-alpha.2"
  sha256 "de18a0bcdeb641ebc9c1bf1fd5465728f89d5fc750d1c0d96ff8faf374c1acf5"

  url "https://github.com/omsingh02/telepad/releases/download/v#{version}/telepad-v#{version}-macos-universal.dmg"
  name "Telepad"
  desc "Use an Android phone as a trackpad, keyboard and media remote"
  homepage "https://telepad-app.vercel.app"

  depends_on macos: ">= :big_sur"

  app "Telepad.app"

  zap trash: [
    "~/Library/Application Support/Telepad",
    "~/Library/LaunchAgents/io.github.omsingh02.telepad.plist",
  ]

  caveats <<~EOS
    Telepad lives in the menu bar. Click its icon, then "Pair a phone...", and scan the QR code with the Telepad app.

    macOS asks to let Telepad control the computer (System Settings > Privacy & Security > Accessibility): allow it,
    or the phone cannot type or click.

    This build is not notarized yet. If macOS says it cannot verify Telepad, run
      xattr -dr com.apple.quarantine /Applications/Telepad.app
    or choose Open Anyway in System Settings > Privacy & Security.
  EOS
end
