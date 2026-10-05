# ShieldBlock Mobile

Android ad, tracker and malware blocker that applies **uBlock Origin's filter lists** through a **local VPN**.
No root. No account. Nothing is sent anywhere except normal DNS lookups and the list downloads.

## How it works

- A local `VpnService` routes only a virtual DNS address into the app. Normal traffic does not pass through it.
- Each DNS lookup is checked against the filter set. Blocked names resolve to `0.0.0.0` / `::`. Everything else goes to your network's own DNS servers.
- Lists come from uBlock Origin's own catalogue, `assets/assets.json` in [gorhill/uBlock](https://github.com/gorhill/uBlock). Every list uBlock enables by default is downloaded (uBlock filters, EasyList, EasyPrivacy, URLhaus, Peter Lowe's list, ...). Lists added or moved upstream are picked up automatically.
- Updates: a background job runs about every 12 hours. Each list refreshes only when its own `updateAfter` age is reached, using ETags to skip unchanged files. Also on app open and via the **Update filter lists now** button.

## YouTube ads (experimental, off by default)

YouTube serves ads and video from the same `googlevideo.com` hostnames, so DNS filtering cannot block them cleanly. The **YouTube ads (experimental)** switch adds the community list [kboghdady/youTube_ads_4_pi-hole](https://github.com/kboghdady/youTube_ads_4_pi-hole) (`youtubelist.txt`, about 15,900 hostnames, refreshed daily).

- Expect some ads to still play. Hostnames rotate, and server-side ad insertion cannot be blocked by DNS at all.
- A video may occasionally fail to load. Switch the option off, or add the failing hostname to the **Allowlist**.
- `s.youtube.com` is always allowed while the option is on, because blocking it breaks playback.
- The unfiltered "crowd list" from that project is not used. It is more likely to break videos.

## Limits (DNS-level blocking)

Supported rules: `||domain^` (with `$third-party`, `$important`, `$all`), `@@||domain^` exceptions, hosts-format lines, plain domain lines.

Not applicable without a browser extension: cosmetic filters (`##`), scriptlets, URL-path rules, resource-type rules, and ads served from the same domain as the content (e.g. YouTube video ads). For full uBlock Origin on Android, use Firefox with the uBlock Origin add-on in addition to this app.

## Install on your phone (no Android Studio)

1. Create a GitHub repository and push this folder to it (branch `main`).
2. Open the **Actions** tab, run **Build APK** (it also runs on every push).
3. Open the finished run, download the **ShieldBlockMobile-apk** artifact, unzip it, send `ShieldBlockMobile-release.apk` to your phone.
4. Tap the APK. Allow "Install unknown apps" for your file manager or browser when asked.
5. Open ShieldBlock Mobile, tap **Start protection**, accept the VPN prompt.

Optional: push a tag (`git tag v1.0.0 && git push --tags`) and the APK is attached to a GitHub Release. Download it straight from your phone's browser.

All builds use the same signing key (`app/shieldblock.jks`), so a new APK installs over the old one and keeps settings. The key is public in the repo. That is fine for personal sideloading. Replace it with your own before distributing the app.

## Build locally

Requirements: JDK 17, Android SDK (platform 34), Gradle 8.9.

```
gradle assembleRelease
# app/build/outputs/apk/release/ShieldBlockMobile-release.apk
```

## Troubleshooting

- Ads still load: turn **Private DNS** off (Settings > Network & internet) and **Use secure DNS** off in Chrome. Both bypass the VPN's DNS.
- Android allows one VPN at a time. Disconnect other VPN apps first.
- A site breaks: add its domain under **Allowlist**.
- Protection stops on reboot: keep **Start protection on boot** on and exclude the app from battery optimisation.

## Project layout

| File | Purpose |
|---|---|
| `ShieldVpnService.kt` | TUN setup, DNS interception, forwarding |
| `Packets.kt` | IPv4/UDP/DNS parsing and response building |
| `Filters.kt` | uBlock/hosts rule parser and domain matcher |
| `Updater.kt` | Fetches uBlock `assets.json` and the lists, ETag-aware |
| `UpdateJobService.kt` | 12-hour background refresh |
| `MainActivity.kt` | UI |
