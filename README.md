# geoip

Lookup the geographical location of IP numbers. No need for
MaxMind. DIY database builder. Build an "ip number to country look up"
database from trawling whois records. Query that database to lookup
ip's and find out what country they are located in. Export the
database to a number of different formats including plain SQL for
integration into existing systems.

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

`start` and `end` define the IP number range to trawl. `db` can be
used to specify the database file to save the data to, and to run
queries against.

`query` can be used to query the database for a particular IP.

`threads` defines how many whois worker threads will be spawned at
once to walk the IP number space. The default is 512 and will use all
your cores and hyperthreads to trawl the whois records. Be warned you
may be blocked for some time by certain registries if you hit them too
hard, too often.

## Examples

Trawling the entire whois database can take some time. Trawling it
agressively still takes a few hours. To speed things up we are going
to limit our database to a part of the IP number space that is fairly
quick to trawl.

First we have no database.

    $ ls -alF db.edn
    ls: cannot access db.edn: No such file or directory

Lets build one by trawling all of 124.*.*.* and 125.*.*.*

    $ lein run -- --start 124.0.0.0 --end 125.255.255.255
    9.59% (49/511)
    ...

Wait until the IP space has been crawled. It should only take a few
minutes on a modern machine. Now you should have a database.

    $ du -sh db.edn
    2.4M	db.edn

Make some queries to the database.

    $ lein run -- --query 124.0.0.0
    loading... db.edn
    124.0.0.0 => :au

Returns a keyword of the ISO 2 letter country code. `:au` is Australia.

    $ lein run -- --query 125.0.0.0
    loading... db.edn
    125.0.0.0 => :jp

This IP is from Japan.
You can query many IPs at once.

    $ lein run -- --query 125.0.0.0,125.1.0.0,125.2.0.0,125.3.0.0
    loading... db.edn
    125.0.0.0 => :jp
    125.1.0.0 => :jp
    125.2.0.0 => :au
    125.3.0.0 => :au

you can query a range.

    $ lein run -- --query 125.1.0.0-125.2.0.0
    ...

or you can scan a range and get the locations where the country zone changes.

    $ lein run -- --changes 124.0.0.0-125.255.255.255
    loading... db.edn
    124.0.0.0 => :au => 124.255.255.255
    125.0.0.0 => :jp => 125.1.255.255
    125.2.0.0 => :au => 125.255.255.255

### Bugs

Plenty. It's too slow. The dataformat is poor. scanning needs to be
first class. store the points where the countries change.

## License

Copyright © 2016 Crispin Wellington

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
