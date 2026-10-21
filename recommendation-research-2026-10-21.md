# Recommendation architecture research

Date: 2026-10-21

Sources inspected:
- https://github.com/Clash-Projects/LastWave-Native
- https://github.com/maxrave-dev/SimpMusic
- https://github.com/Spotui/Spotui

## LastWave-Native

Relevant files:
- app/src/main/java/com/lastwave/app/playback/MusicPlayer.kt
- app/src/main/java/com/lastwave/app/data/generate/GenerateRepository.kt
- app/src/main/java/com/lastwave/app/data/generate/RecommendationEngine.kt
- docs/superpowers/specs/2026-10-16-related-graph-design.md

Observed design: context-aware radio uses a primary related-track source, up to three fallback seeds, direct artist radio searches, and recent high-engagement artists. It filters duplicate versions, skips, queued items, and caps artists. GenerateRepository also expands multiple explicit seeds concurrently, uses YouTube related tracks, local seeds, charts, and optional Last.fm. RecommendationEngine has multiple source families (YouTube feed/radio, similar tracks, similar artists, artist top tracks, genres, tags, discovery) and bucket interleaving. A persistent related-track graph stores cross-seed referrals with decayed scoring.

## SimpMusic

Relevant behavior: playback primarily calls YouTube radio/related endpoints via getRadioFromEndpoint() and getRelated(). It has local analytics/history and queue recovery, but no comparable local multi-source varied AutoPlay planner was found. It is mostly a YouTube Music client that delegates recommendation choice to YouTube.

## Spotui

Relevant files:
- app/src/main/java/com/music/spotui/data/recommendation/SpotifyRecommendationEngine.kt
- app/src/main/java/com/music/spotui/data/api/Api.kt
- app/src/main/java/com/music/spotui/ui/viewmodel/PlayerViewModel.kt

Observed design: SpotifyRecommendationEngine builds a cached taste profile from top tracks/artists, artist affinity, artist genres, top-track pool, and short-term artists. It gathers candidate buckets: seed artist top tracks, same-album tracks, genre-neighbour artist top tracks, and user top tracks. Candidates receive composite scores for source relevance, artist affinity, genre overlap, popularity similarity, and recency. Diversification caps each artist at three and interleaves source buckets when one bucket repeats. PlayerViewModel uses up to five queue seeds when extending autoplay. API prefers provider radio/recommendations, then falls back to a native local recommender and artist radio.

## BitChord implication

Keep BitChord UI, player, YouTube integration, Radio separation, diagnostics, and existing duplicate protections. Adapt Spotui/LastWave ideas without hard-coded personal artists or genres: build independent candidate buckets from direct long-term-history artists, YouTube radio for multiple seeds, YouTube personalized/home discovery, and generic mood/genre discovery; then score/interleave buckets, enforce artist cooldown and version filtering, and reserve bounded unfamiliar-artist slots. Provider results remain inputs, not the entire queue.
