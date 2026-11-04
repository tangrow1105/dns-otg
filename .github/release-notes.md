**Install:** download `DNS-OTG-1.0.5.apk` below and open it on your phone (Android 8.0 or newer). It updates earlier versions in place and keeps you signed in.

### New
- **Set up an iPhone or Mac:** on an endpoint's Resolvers screen, Help Me Configure can now send a setup file. Opening it on the iPhone, iPad or Mac installs Control D for that endpoint.
- **Control D network status** in Preferences: whether all of Control D's locations are up, with API, DNS and proxy for each, and which one serves you
- **Filter levels change in one request,** like the dashboard, so a switch can't be left half done. Malware's levels now stack the way Control D's do (Balanced adds IP malware, Strict adds AI malware).

Ideas from @karlohrvacic's issue, thanks!

### Fixed
- Profile options: Safe Search and Restricted YouTube can be switched on and off again
- Profile options: EDNS Client Subnet saves its choice (No ECS, Auto or Custom)
- Profile options: Block Response set to 0.0.0.0, and EDNS Client Subnet set to No ECS, no longer show as switched off
- Profile options: the Custom choices for Block Response and EDNS Client Subnet now ask for their IPs, and Branded turns on Control D's block page
- Profile options: switches send exactly what the dashboard sends, and TTLs accept whole seconds only

Not affiliated with Control D.
