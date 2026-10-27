**Install:** download `DNS-OTG-1.0.4.apk` below and open it on your phone (Android 8.0 or newer). It updates earlier versions in place and keeps you signed in.

### Changed
- **Simpler sign-in:** Open Control D takes you to the dashboard in your browser, where you sign in as usual (any account type) and create an API token, then the app uses it. The in-app login page is gone: it could come up blank on newer Android versions, and this way the app never sees your password or web session.

### Improved
- Activity log: the screen stays on while Live is streaming, and goes back to normal when Live pauses or stops
- Live: when the live log isn't available for your token, the chip says "Live · failed" and a tap turns it off
- Statistics opens on an up-to-date time window, and a custom range that includes today grows up to now on refresh
- The chart's Redirected line now includes spoofed queries, like the Redirected tile

### Fixed
- Editing a rule folder no longer turns a disabled folder back on
- Domain Test no longer says a domain "resolves normally" when it doesn't exist or the endpoint is disabled
- The status check shows an error when you're offline, instead of "Not using Control D"
- Creating several endpoints at once can't create one twice if a step fails
- Failed exports no longer leave an empty file behind, and CSV exports are safe to open in spreadsheets
- Messages like "Profile deleted" now show after the screen closes
- Tapping back twice quickly no longer leaves an empty screen
- Widgets (beta): one update at a time, no more getting stuck on "Updating…", and they refresh after a storage region change

Thanks to @karlohrvacic for all of these!

Not affiliated with Control D.
