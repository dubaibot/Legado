# Grouped Local Library Design

Updated: 2026-11-17

## Architecture

BookGroup defaults use existing reserved negative IDs plus an Other ID. BookGroupRules supplies equivalent SQL and Kotlin classification with video/audio/image/text priority. Database opening and restore merge defaults while retaining existing custom groups. Enabled defaults remain visible even when empty. Bookshelf tag management reads enabled groups and scopes its operations to the current group.

LocalMediaNames identifies supported media extensions and compares numeric filename runs without integer overflow. LocalMediaDirectory marks local directory books in serialized variables, lists only current-directory files and builds stable URI chapters. The reader obtains image markup and ImageProvider copies URI content into its existing image cache. VideoPlay loads directory chapters and sends content/file addresses directly to the player; ExoPlayer selects progressive extraction for local schemes.

## Import Drafts

ImportBookViewModel prepares books and media references without library insertion. ImportBookActivity presents a per-item confirmation form, group selector and cover picker. Draft restoration stores media URIs as strings. Final confirmation validates names, URI conflicts and cover readability, then saves books and chapters in a transaction. Imported local covers are copied into application storage. Existing identical URIs are skipped; identical name/author pairs require renaming.

## Search

BookSearchScreen exposes the group picker from the toolbar and uses a fixed three-column lazy grid inside the dialog. SearchActivity owns the blank-keyword search guard so scope selection can prepare the next search. Selected groups sort stably after Comprehensive. Recommendation cards reserve two title lines and retain author attribution.

## Verification

Unit tests cover pure type-group classification, natural file ordering and media extension recognition, together with existing search matching/filter tests. Compile and debug packaging validate Room queries, Android components and resources. Device checks cover SAF permission persistence, directory reads, image rendering, video seek restoration, rotation during confirmation and local/network covers.
