# Python Category And Route Compatibility

Updated: 2026-11-16

The Python adapter requests homeContent with filters enabled, retains filters and independently loads homeVideoContent recommendations. Category requests decode selected filter JSON into an extend dictionary. Two-argument module functions remain supported for installed plugins.

VideoCategory carries typed filter groups. The homepage renders one horizontally scrollable child row per group. Selected values reset with the main category, remain active across pages and participate in both source and ViewModel cache keys. A request version prevents earlier category responses from replacing the current results.

Python detail normalization retains structured and CMS fields. PyResultParser delegates route parsing to TvBoxResultParser, which preserves empty group positions, splits episode labels at the first dollar character and supports nested episodes with inherited flags and headers. PlayLine and RssEpisode retain original flags through activity initialization and episode changes. HomeBridge passes the flag into playerContent; playback parses headers and parse mode through the shared result parser.

Verification uses Python unittest, PyResultParserTest and TvBoxResultParserTest followed by assembleDebug. Actual source network behavior and Android layout interaction require device testing.
