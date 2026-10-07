# MeshUp Privacy Policy

> **DRAFT — not yet published.** Placeholders in `[brackets]` must be filled in, and the text should be reviewed (ideally by a lawyer) before release. It describes the app's behaviour as of `main` on 2026-10-08.

*Last updated: [publication date]*

MeshUp is an offline-first messenger. It lets phones near each other exchange messages over Bluetooth without accounts, phone numbers or servers. This policy explains what the app stores, what it shares, and with whom.

## Summary

- **No account, no phone number, no email.** You choose a display name; that's it.
- **We (the developers) receive no data from the app.** There are no MeshUp servers, no analytics and no ads.
- **Offline by default.** Bluetooth mesh messaging works with no Internet connection. **Internet features are off until you turn them on** in Settings.
- **One known exception:** QR-code scanning uses a Google library (ML Kit) that may send anonymous usage statistics to Google, even with Internet features off. See [Third-party components](#third-party-components).

## What is stored on your device

Everything below stays on your phone. Nothing is uploaded to us.

| Data | Purpose | Protection |
|---|---|---|
| Identity keys (Noise and signing keys) | Prove your identity to people you talk to; encrypt private messages | Stored in Android encrypted storage, backed by the Android Keystore. Excluded from cloud backup and device-to-device transfer. |
| Display name | Shown to people nearby | Stored locally |
| Private message history | Show your conversations | Message content is encrypted at rest (AES-GCM, Android Keystore). Some metadata — such as timestamps, conversation identifiers and delivery status — is stored unencrypted on the device. |
| Unsent private messages | Deliver a message when the other person comes back in range | Encrypted like message history; kept for at most 1 hour, then marked as failed |
| Favourites and joined rooms | Recognise people you've marked as favourites; remember your rooms | Stored locally |
| Settings | Your preferences (for example, whether Internet features are on) | Stored locally |
| Diagnostic logs | Help diagnose problems on your own device | Release builds keep only warnings and errors, with identifiers replaced by short tags. Logs stay on the device and are not sent to us. |

Public mesh messages and room messages are kept in memory only and are gone when the app closes.

## What people nearby can see

When MeshUp is running, other MeshUp and bitchat users within Bluetooth range (and, through relaying, a few hops further) can see:

- Your **display name**.
- Your **public keys** and a device identifier used by the mesh protocol. These let others recognise your device while you are nearby.
- **Messages you send** to public chat or rooms.
- **Private messages** you send them. Private messages are end-to-end encrypted (Noise protocol); phones that relay them cannot read them.

Your phone also relays other people's messages to help the mesh work. Relayed private messages are encrypted and cannot be read by your phone.

## Internet features (off by default)

If you turn on **Internet features** in Settings, MeshUp also uses the Internet:

| Feature | What is sent, and to whom |
|---|---|
| **Tor** (on by default within Internet features) | Internet traffic is routed through the Tor network to hide your IP address from the services below. |
| **Nostr relays** | Encrypted private messages to favourites who are not nearby, and messages in location channels, are sent through public Nostr relays run by third parties. |
| **Location channels** | Your **approximate area** (a "geohash", not your exact coordinates) is shared with Nostr relays and with other people in that channel. |
| **Place names** | To name a location channel, your coordinates may be sent to your device's built-in geocoder or to OpenStreetMap Nominatim. |
| **Relay list and update checks** | The app downloads a public list of Nostr relays and checks for app updates (GitHub). |

When you turn Internet features off, the app stops making new Internet connections. A restart fully closes connections that were already open; the app tells you when this is recommended.

## Permissions

- **Bluetooth (nearby devices):** to find and talk to nearby MeshUp users.
- **Location:** Android requires location permission for Bluetooth scanning on some versions. MeshUp does not record your location for Bluetooth. Location is used for location channels only if you turn on Internet features and use that feature.
- **Notifications:** to tell you about new messages.
- **Battery optimisation exemption (optional):** so the mesh keeps working in the background.

## Third-party components

- **Google ML Kit (barcode scanning):** used when you scan a QR code to verify a contact. It runs on the device, but it may send anonymous usage statistics to Google. We plan to replace it with a fully offline scanner.
- **Nostr relays and the Tor network:** used only when Internet features are on, as described above. They are run by third parties with their own policies.

## Your control

- **Edit your display name** at any time in Profile.
- **Reset identity:** Profile → *Reset identity…* (type `reset` to confirm). This erases your identity, messages and contacts.
- **Emergency wipe:** triple-tap the app title to instantly erase all data.
- **Turn Internet features off** at any time in Settings.
- **Uninstall:** removes all MeshUp data from your device.

Because we hold no data about you, there is nothing for us to export or delete on a server.

## Children

MeshUp does not collect personal information from anyone, including children. [Set the minimum age for your distribution channel, e.g. 13+.]

## Source code

MeshUp is free software under the GNU General Public License v3.0. You can review the source code that implements everything described here at [repository URL].

## Changes

If this policy changes, the "Last updated" date will change and the new policy will ship with the app and be published at [policy URL].

## Contact

[Developer or organisation name]
[Contact email]
