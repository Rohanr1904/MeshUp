# 06 — UX Specification

## Design goal
The app should feel like a modern messenger, not a networking utility.

## Primary navigation
- Home
- Chats
- Rooms
- People
- Profile

## First launch
1. Welcome
2. Explain offline messaging in one screen
3. Choose display name
4. Generate local identity
5. Explain Nearby permissions
6. Explain battery behavior
7. Enter Home

## Home
Show:
- mesh status
- nearby people count
- nearby rooms
- recent conversations
- Create Room CTA

Example:

Nearby
17 people

Nearby Rooms
- Tech Meetup — 37 people
- Concert — 92 people
- General — 12 people

[Create Room]

## Chat
Familiar message bubbles and input behavior.

Delivery states:
- Sending
- Waiting for route
- Relaying
- Delivered
- Failed

Optional advanced information:
"Delivered • 2 hops"

## Rooms
Room creation:
- Name
- Description
- Visibility
- Password
- Expiration
- Message retention

## People
Show friendly identity:
- Display name
- Nearby state
- Trust state
- Open chat
- Verify contact

Do not expose raw MAC addresses, public keys or internal IDs.

## Profile
- Display name
- Avatar
- Privacy controls
- Identity/verification
- Export/backup only where secure and intentional

## Privacy
Provide understandable controls for:
- nearby visibility
- read receipts
- typing indicator
- message retention
- Internet transport
- location sharing
- notification preview

## Diagnostics
Technical mesh information belongs in an advanced screen.

## Accessibility
Support:
- dynamic text size
- screen readers
- adequate contrast
- touch targets
- reduced motion
- keyboard navigation where relevant

## Visual direction
Modern Material 3.
Premium and calm.
Avoid excessive neon/terminal aesthetics.
Dark mode and light mode.
