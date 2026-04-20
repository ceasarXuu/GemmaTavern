[English](./README.md) | [简体中文](./README.zh-CN.md)

# Gemma Tavern

Gemma Tavern is a local roleplay chat app for Android. It aims to bring a Tavern / SillyTavern-style experience onto mobile devices, so you can manage character cards, configure personas, and run immersive multi-turn conversations directly on-device.

The core direction of this project is simple:
make local models not just "runnable," but genuinely usable in long-form roleplay chat scenarios.

## Highlights

### 1. Local-first and privacy-friendly

Gemma Tavern is built around on-device model execution. The focus is not cloud chat, but pushing as much of the roleplay experience as possible onto the device itself.

- Conversations are closer to an offline usage model
- Personal settings, chat history, and character data stay on-device more easily
- Better suited for users who care about privacy, responsiveness, and control

### 2. A Tavern-style experience on mobile

This is not a simple demo that only sends a single message. It is a mobile product shape designed around persistent roleplay chat.

- Supports continuous multi-turn conversations
- Supports session management, pinning, resuming chats, and model switching
- Supports a clear workflow across character, session, and persona pages

### 3. Multimodal chat support

Gemma Tavern supports images and audio in the chat pipeline, not just plain text conversations.

- Image messages
- Audio messages
- Continued conversations around shared media

### 4. Built for roleplay, not a generic AI shell

Gemma Tavern prioritizes characters, settings, context, and immersion rather than acting as a generic AI toolbox.

- Character card creation and editing are first-class paths
- Personas directly participate in chat context
- The experience is organized around how you interact with a character, not around task lists or model showcase screens

### 5. Compatible with the SillyTavern ecosystem

If you already have ST character cards or chat logs, this project is not asking you to start from scratch.

- Supports ST v2 character-card related compatibility
- Supports ST PNG character card encoding and decoding
- Supports `jsonl` chat import and export
- Leaves clear architectural space for connecting with existing Tavern workflows

### 6. Characters and personas are both treated seriously

Gemma Tavern separates "who the character is" from "who you are."

- Characters can be created, edited, imported, and exported independently
- User personas can be managed in multiple versions
- Default persona, avatar, description, insertion position, and depth are all configurable

## What You Can Use It For

- Have persistent multi-turn conversations with local characters on your phone
- Continue interactions around images or audio inside chat
- Create your own character cards and iterate on them repeatedly
- Import existing ST character cards and keep using them
- Prepare different personas for different themes and switch roleplay styles
- Maintain long-lived sessions instead of starting from an empty prompt every time

## Core Features

- Roleplay chat: multi-turn conversations powered by local models
- Multimodal messages: images, audio, and other media can enter the chat pipeline
- Session management: create, resume, pin, delete, import, and export sessions
- Character system: built-in characters, custom characters, character editor, and media asset management
- Persona system: multiple personas, default persona, avatar editing, and text injection strategies
- ST interoperability: character card parsing, PNG character card support, and chat-log interop
- Model access: retained local model library and model switching capabilities
- Multi-language support: current resources cover Simplified Chinese, English, Japanese, Korean, and more

## References and Acknowledgements

This project draws inspiration from the following open-source projects. Thanks to the original projects and their contributors for their work:

- [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)
- [SillyTavern/SillyTavern](https://github.com/SillyTavern/SillyTavern)

## License

This project currently uses the Apache 2.0 license. See [LICENSE](./LICENSE) for details.

## Git Distribution

This project is currently distributed only through the Git repository and GitHub Releases. It is not currently distributed through any app store.

- Source build and local development instructions: [DEVELOPMENT.md](./DEVELOPMENT.md)
- Pre-release checks and sideloading notes: [RELEASING.md](./RELEASING.md)
- The Android release APK is intended for source distribution and real-device sideload verification, not as an app-store release package

## Documentation

- Contribution workflow: [CONTRIBUTING.md](./CONTRIBUTING.md)
- Bug reporting guide: [Bug_Reporting_Guide.md](./Bug_Reporting_Guide.md)
- Skills and function extension: [Function_Calling_Guide.md](./Function_Calling_Guide.md) and [skills/README.md](./skills/README.md)
- Architecture and verification document index: [docs/README.md](./docs/README.md)

Public documents in this repository are intentionally limited to stable, reusable content that is suitable for open-source collaboration. Internal plans, ops notes, and one-off reports are no longer treated as part of the public repository docs.
