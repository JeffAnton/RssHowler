# RssHowler
Quick and simple database driven podcast downloader

Fetures of RssHowler

Directories are created matching feed names (with some removed descriptions)

Files are named to match the training url component - can be changed

Feed URLs are automatically renamed when moved by producer
Feed URLs are automatically disabled (flags set to 0) on 410 error status

Flags on feeds:
0 (no bits) - do nothing with this feed - just remember it
1 - polling download and parse feed
2 - update podcasts table with feed items
3 (1 & 2) - normal operation - download feed and items
4 - special name handling - make file names from title - needed for
     feeds with poor filename generation
7 (1 & 2 & 4) normal download with filename correction
8 - do not download feeds but do update podcasts table - to skip old items
11 (1 & 2 & 8) update podcasts table without downloading
16 - HEAD operation only to check for working URLs
32 - Always fetch feed - do not poll
64 - prepend date to filename if possible

Feed table has a since date which will only download items whose pubDate
is after the set since date
