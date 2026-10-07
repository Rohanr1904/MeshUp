# 09 — License Notes

## Purpose
This document records licensing items that must be reviewed before distribution.

## Upstream
The project is based on permissionlesstech/bitchat-android.

The repository's actual LICENSE.md and current repository metadata are authoritative for the code being forked.

## Critical rule
Do not remove, weaken or replace upstream license notices without a legally reviewed reason.

## Commercial distribution
Before distributing a proprietary or commercial application based on the fork:
- determine all obligations of GPLv3 code
- identify whether any newly added modules can be distributed under different terms
- track all upstream files copied or modified
- preserve required notices
- review dependency licenses
- review transitive dependency licenses
- document modifications
- obtain legal advice on the intended distribution model

## Dependency inventory
Maintain an up-to-date dependency/license inventory for:
- Android/Jetpack
- Kotlin
- crypto libraries
- serialization
- networking
- UI libraries
- testing libraries
- build plugins

## Separation rule
Do not assume:
- "forked from GitHub" means unrestricted
- "public repository" means public domain
- a README statement overrides LICENSE.md

## Release gate
No public commercial APK/AAB release until licensing has been reviewed and approved for the intended distribution model.
