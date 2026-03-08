# Craftword
Craftword is a plugin for PaperMC that allows you to import online crosswords from a number of websites, and turn them into big in-game crosswords for players to work on together. 

The plugin does require a resource-pack, which is included by default. If you want to put a copy on your client, you can find it at:
``src/main/resources/craftword.zip``

To access the letters, sign a *Book and Quill* with the title ```Alphabet```

## Commands
``/craftword:build_new <dimension> <at: x y z> <site> [<yyyy-mm-dd>]``</br>
Creates a new crossword instance at the dimension and location. </br>
Site is the website to fetch from, the date will default to today if blank. </br>
If the crossword already exists, it will load it from ``plugins/Craftword/crosswords`` </br>
Can fail if the new crossword overlaps with an existing crossword, or if it can't access the site.

``/craftword:remove <dimension> <at: x y z>`` </br>
Removes any crosswords found at the location specified. </br>
Can be run on unloaded chunks </br>
If it manages to miss any entities you can run it close to a missed entity, and it will finish the job.

``/craftword:clear_cache`` </br>
Deletes all the crossword data you've downloaded from websites that aren't being used. </br>
This does not remove in world crossword placements. </br>
Not super necessary unless you set up a daily system and accumulate lots of files

``/craftword:hint_limits <min> <max>`` </br>
Sets the min and max number of players to trigger a grid check. </br>
Number needed for grid check = Clamp(Number of Players Online, min, max) </br>
Regardless of input, Min >= 1, Max >= Min </br>


### WARNING
Just because a website's crossword can be fetched doesn't mean they allow it. If your server is big or generates profit, you should seek permission from the website before using their crosswords. Use at your own risk, this is not legal advice.</br>

## Credits
[Negative Space Font by AmberWat](https://github.com/AmberWat/NegativeSpaceFont) </br>
[Monocraft by IdreesInc](https://github.com/IdreesInc/Monocraft) </br>
[xword-dl by thisisparker](https://github.com/thisisparker/xword-dl) </br>

</br></br>
### AI Disclaimer
Some parts of this project were written by or with the help of AI, most notably translating the python from xword-dl to Java. 
AmuseLabs crosswords in particular require a lot of parsing that was all done as a translation from xword-dl by Claude Sonnet 4.6

