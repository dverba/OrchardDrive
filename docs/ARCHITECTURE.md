# Architecture

```
AAOS Media Center / media keys / voice
        │  (MediaBrowser + MediaController, via Media3's legacy compat layer)
        ▼
AutomotiveMusicService (MediaLibraryService)
   ├── MediaLibrarySession ── AutomotiveLibraryCallback
   │                              └── LibraryTree ── MusicRepository ── MusicProvider
   └── Player  ◄── MusicPlaybackProvider.createPlayer()
```

## Decisions

**Playback is a Media3 `Player`, not a bespoke playback interface.** The plan's
`MusicPlaybackProvider { play(); pause(); … }` would need a hand-written sync layer between
MusicKit, the provider and the MediaSession. Instead `MusicPlaybackProvider` returns a `Player`.
The fake implementation is ExoPlayer. An Apple implementation would be a `SimpleBasePlayer`
wrapping MusicKit's player: Media3 then handles all MediaSession/AAOS state publishing, and
the only Apple-specific code is the MusicKit ↔ `Player.State` mapping.

**`MusicProvider` returns internal models only** (`Song`, `Album`, `Artist`, `Playlist`,
`SearchResults`). Changes from the original plan: `search` returns categorized `SearchResults`;
`getRecentlyPlayed` returns `List<Song>`; added `getSongs`, `getArtist`, `getArtistAlbums`;
lookups return `null` for unknown ids. Pagination is not modelled yet; Apple's API pages
library endpoints, so add cursor/offset parameters with the Apple provider.

**Media IDs carry browse context** (`song:s3@album:alb1`). AAOS sends only the tapped id;
the context lets `onSetMediaItems` queue the rest of the album/playlist/list. See `MediaIds.kt`.

**`LibraryTree` is Android-free** so hierarchy and queue logic are JVM unit tested;
`MediaItemMapper` is the only place Media3 `MediaItem`s are built.

**The Activity never owns playback.** It is a regular `MediaController` client, like AAOS.

## Content style

Root hints: browsable = grid, playable = list. Home/Library tabs override to list (category
folders have no artwork). Albums/playlists show tracks as a list. Search results carry group
titles.
