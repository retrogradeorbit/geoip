# geoip

Build a ip number to country look up database from trawling whois
records. Query that database to lookup ip's and find out what country
they are located in. Export the database to a number of different
formats including plain SQL for integration into existing systems.

## Installation

Download. Run ```lein bin```. Copy ```target/geoip``` to your path.

## Usage

Running under lein:

    /path/to/geoip$ lein run -- --help
      -h, --help
      -v, --version
      -s, --start IP       1.0.0.0  Starting IP number
      -e, --end IP         2.0.0.0  Ending IP number
      -d, --db DBFILE      db.edn   The database file to use
      -q, --query IP                Lookip an ip in the database
      -t, --threads COUNT  512      The maximum number of whois trawling threads

Running from built jar :

    $ java -jar geoip-0.1.0-standalone.jar --help

Running from installed bin:

    $ geoip --help

## Options

Basic options:

    -s, --start IP       1.0.0.0  Starting IP number
    -e, --end IP         2.0.0.0  Ending IP number
    -d, --db DBFILE      db.edn   The database file to use
    -q, --query IP                Lookip an ip in the database
    -t, --threads COUNT  512      The maximum number of whois trawling threads

`start` and `end` define the IP number range to trawl. `db` can be used to specify the database file to save the data to, and to run queries against.

`query` can be used to query the database for a particular IP.

`threads` defines how many whois worker threads will be spawned at once to walk the IP number space. The default is 512 and will use all your cores and hyperthreads to trawl the whois records. Be warned you may be blocked for some time by certain registries if you hit them too hard, too often.

## Examples

...

### Bugs

...

### Any Other Sections
### That You Think
### Might be Useful

## License

Copyright © 2016 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
