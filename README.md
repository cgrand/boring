# The Boring Library

A library to bore tunnels.

## Usage

```clj
(require '[net.cgrand.boring :as boring])
(boring/connect "(ssh:user@ssh-server)target:5555" {:ssh/key (java.io.File. "key.pem")})
```

## License

Copyright © 2018 Christophe Grand

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
