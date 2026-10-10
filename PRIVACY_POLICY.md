> **DRAFT — not yet published.** It will be published at https://rohanr1904.github.io/nearbird/ after legal review. Prepared for review by a qualified lawyer; not legal advice.

# NearBird Privacy Policy

*Last updated: [publication date]*
*Published at: https://rohanr1904.github.io/nearbird/*

NearBird is an offline-first messenger. It lets phones near each other exchange messages over Bluetooth without accounts, phone numbers or servers. This policy explains what the app stores, what it shares, and with whom.

## Summary

- **No account, no phone number, no email.** You choose a display name; that is all.
- **The developers receive no data from the app.** There are no NearBird servers, no analytics and no ads.
- **Offline by default.** Bluetooth mesh messaging works with no Internet connection. **Internet features are off until you turn them on** in Settings.
- **One known exception:** QR-code scanning uses a Google library (ML Kit) that sends metrics to Google, even with Internet features off. See [Third-party components](#third-party-components).
- **For adults only.** NearBird is for people aged 18 and over. See [Children](#children).

## What is stored on your device

Everything below stays on your phone. Nothing is uploaded to the developers.

| Data | Purpose | Protection |
|---|---|---|
| Identity keys (Noise and signing keys) | Prove your identity to people you talk to; encrypt private messages | Stored in Android encrypted storage, backed by the Android Keystore. Excluded from cloud backup and device-to-device transfer. Android backup is turned off for the app. |
| Display name | Shown to people nearby | Stored locally |
| Private message history | Show your conversations | Message content is encrypted at rest (AES-GCM, Android Keystore). Some metadata, such as timestamps, conversation identifiers and delivery status, is stored unencrypted on the device. |
| Images, voice notes and files you send or receive in private chats | Show them in your conversations | Stored in the app's private storage on your device, up to a size limit, and removed when the related conversation or message is deleted. |
| Unsent private messages | Deliver a message when the other person comes back in range | Content is encrypted like message history; scheduling details (such as time and retry count) are not. Kept for at most 1 hour, then marked as failed. |
| Favourites and joined rooms | Recognise people you have marked as favourites; remember your rooms | Stored locally |
| Settings | Your preferences (for example, whether Internet features are on) | Stored locally |
| Diagnostic logs | Help diagnose problems on your own device | Release builds keep only warnings and errors. Most identifiers in them are replaced by short tags, but a few may still appear in full. Logs stay on the device and are not sent to the developers. |

Private message history is limited to the most recent 1,000 messages per conversation and 20,000 messages in total; older messages are deleted automatically.

Public mesh messages and room messages are kept in memory only and are gone when the app closes.

## What people nearby can see

When NearBird is running, other NearBird and bitchat users within Bluetooth range (and, through relaying, a few hops further) can see:

- Your **display name**.
- Your **public keys** and a device identifier used by the mesh protocol. These let others recognise your device while you are nearby.
- **Messages you send** to public chat or rooms.
- **Private messages** you send them. Private messages are end-to-end encrypted (Noise protocol); phones that relay them cannot read them.

Your phone also relays other people's messages to help the mesh work. Relayed private messages are encrypted and cannot be read by your phone.

## Internet features (off by default)

If you turn on **Internet features** in Settings, NearBird also uses the Internet:

| Feature | What is sent, and to whom |
|---|---|
| **Tor** (on by default within Internet features) | Internet traffic is routed through the Tor network to hide your IP address from the services below. |
| **Nostr relays** | Encrypted private messages to favourites who are not nearby, and messages in location channels, are sent through public Nostr relays run by third parties. |
| **Location channels** | Your **approximate area** (a "geohash", not your exact coordinates) is shared with Nostr relays and with other people in that channel. |
| **Place names** | To name a location channel, your coordinates may be sent to your device's built-in geocoder (which on many phones is provided by Google Play services) or to OpenStreetMap Nominatim. |
| **Relay list and update checks** | The app downloads a public list of Nostr relays from GitHub and checks GitHub for new app versions. GitHub can see the request, as any website can, unless Tor hides your IP address. |

When you turn Internet features off, the app stops making new Internet connections. A restart fully closes connections that were already open; the app tells you when this is recommended.

## Permissions

- **Bluetooth (nearby devices):** to find and talk to nearby NearBird users.
- **Location:** Android requires location permission for Bluetooth scanning on some versions. NearBird does not record your location for Bluetooth. Location is also used for location channels. Sharing your approximate area with relays happens only if you turn on Internet features and use that feature.
- **Nearby Wi-Fi devices, Wi-Fi state and local network:** to use the Wi-Fi Aware mesh transport on supported phones, and to share the app file with a nearby phone over a hotspot.
- **Microphone:** only to record voice notes you choose to send.
- **Camera:** only to scan a QR code when you verify a contact.
- **Notifications:** to tell you about new messages.
- **Foreground service, run at start-up, keep awake and vibrate:** so the mesh keeps working in the background and the app can alert you.
- **Battery optimisation exemption (optional):** so the mesh keeps working in the background.
- **Internet and network state:** used only when you turn on Internet features, plus the Google component described below.

## Third-party components

- **Google ML Kit (barcode scanning):** used when you scan a QR code to verify a contact. The image is processed on your device and is not sent to Google. However, the ML Kit APIs also send metrics about the performance and utilization of the APIs in this app to Google. This can happen even when Internet features are off. We plan to replace ML Kit with a fully offline scanner.
- **Google Play services (location):** NearBird uses the Google Play services location library to get your location for location channels. Google's own terms and privacy policy apply to that component.
- **Nostr relays and the Tor network:** used only when Internet features are on, as described above. They are run by third parties with their own policies.
- **GitHub:** used only when Internet features are on, for the relay list and update checks.

## Your control

- **Edit your display name** at any time in Profile.
- **Reset identity:** Profile, then *Reset identity…* (type `reset` to confirm). This erases your identity, messages and contacts.
- **Emergency wipe:** triple-tap the app title to erase all data immediately.
- **Turn Internet features off** at any time in Settings.
- **Uninstall:** removes all NearBird data from your device.

Because the developers hold no data about you, there is nothing for us to export or delete on a server.

## Children

NearBird is intended for people aged **18 and over**. Do not use it if you are under 18. The app does not verify age, and it does not knowingly collect personal information from anyone, including children. NearBird lets nearby strangers message you and has no moderation or reporting tools, which is why it is not suitable for children.

## Source code

NearBird is free software under the GNU General Public License v3.0. You can review the source code that implements everything described here at https://github.com/Rohanr1904/nearbird.

## Changes

If this policy changes, the "Last updated" date will change and the new policy will ship with the app and be published at https://rohanr1904.github.io/nearbird/.

## Contact

NearBird maintainers

Contact: nearbirdapp@proton.me

Grievance / privacy contact: nearbirdapp@proton.me
