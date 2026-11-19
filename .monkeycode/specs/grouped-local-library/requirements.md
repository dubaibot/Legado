# Grouped Local Library Requirements

Updated: 2026-11-17

1. The bookshelf SHALL initialize the enabled default groups in order: novels, audio, comics, video, other and all.
2. The library SHALL classify text, downloadable-file and local-text books as novels, image books as comics, audio books as audio and video books as video. Remaining types SHALL enter other.
3. Migration SHALL preserve custom groups and existing books, and hide legacy system groups by default.
4. Tag management SHALL list enabled groups and manage tags as subcategories of the selected group.
5. Local import SHALL accept a selected image or video directory as one library item, with naturally ordered files as chapters.
6. A mixed-media directory SHALL allow the user to select image or video import. Directory import SHALL process matching files in the selected directory.
7. Before confirmation, local import SHALL provide editable name, author, group selection and cover from a local image or HTTP/HTTPS address.
8. Cancellation SHALL discard pending library drafts. Confirmation SHALL validate duplicate URI/name conflicts and commit the batch transactionally.
9. Local image chapters SHALL open in the reader; local video chapters SHALL play from their file/content URI and preserve playback progress.
10. Search SHALL display the multi-group control as multi-group search, provide a toolbar group-list action and show groups in a three-column dialog with a trailing multi-select checkbox.
11. The search group strip SHALL order selected groups after Comprehensive. Empty keywords SHALL allow scope changes without initiating search.
12. Group and recommendation-address text SHALL be black; history text SHALL be gray. Recommendation titles SHALL reserve two lines and cards SHALL show cover, title and author.
