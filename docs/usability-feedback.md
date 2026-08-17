# Real-user usability feedback

This backlog comes from an exploratory test by a non-Android-specialist user. These prompts are
treated as product requirements and routing regressions, not merely as anecdotal examples.

## Implemented in the first usability sprint

- Prefer a direct answer over an unrelated tool or action. Kotlin checks that a selected route is
  semantically relevant before allowing it to execute.
- Answer broad “what can you do?” questions from an authoritative local capability manifest.
- Report battery percentage and temperature without inventing a remaining-hours estimate.
- Propose Camera, Wallpaper Settings, and background-app review with explicit confirmation.
- Open an explicitly named installed app after confirmation; this supports handoff to apps such as
  Spotify, YouTube, or ChatGPT but does not claim to operate their private UI or return their result.
- Replace the jumpy Agent percentage with an indeterminate indicator plus the current named stage.
- Move action benchmark controls off the Agent surface and consolidate microphone controls.

## Planned medium-effort capabilities

- Spotify and YouTube search/deep-link handoff when the user supplies a song or video query.
- WhatsApp message drafts when the recipient is unambiguous, with a preview and confirmation; the
  user remains responsible for pressing Send.
- A guided phone-optimization report that identifies pressure and opens the appropriate Android or
  Samsung management surface. An ordinary app cannot reliably or safely force-stop every other app.
- A fuller conversation-style Agent screen with compact model status and expandable technical data.

## Deferred medium-hard work

- Local photo collage generation with the system photo picker.
- Media pause, resume, and seeking through MediaSession or an opt-in AccessibilityService.
- Retrieving results from another assistant app. Android sharing can hand off a prompt, but it does
  not provide a dependable result channel.
- General UI automation or automatic bulk process termination. These require substantially broader
  privileges and safety evaluation.

## Real-user regression prompts

1. Open camera.
2. How can I change my wallpaper?
3. How much battery life do I have?
4. Please remove unnecessary processes running behind.
5. I generally do not know things to do on my phone; can you help me?
6. Can you do a collage of my photos?
7. Can you open a song from Spotify for me?
8. Can you open a YouTube video and control playback?
9. Can you text my boyfriend from WhatsApp?
10. Can you use the ChatGPT app to get better results?

Unsupported requests must receive a truthful answer or clarification. They must never be mapped to
Storage Settings merely because that is the closest available action.
