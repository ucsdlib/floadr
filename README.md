f(edora) load(e)r
-----------------

command-line utility to use the [fedora4 java client library](https://github.com/fcrepo4-labs/fcrepo4-client) to load data.

example usage:

```sh
$ mvn exec:java -Dexec.mainClass=edu.ucsd.library.floadr.Floadr -Dexec.args="http://localhost:8080/rest ids.txt /path/to/files"
```

where
* `http://localhost:8080/rest` is the baseURL of fcrepo4
* `ids.txt` is a file listing object identifiers
* `/path/to/files` is a directory containing the files to be loaded
