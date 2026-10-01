# Ultrasonic

Ultrasonic is free and open-source music streaming Android client for
[OpenSubsonic API][subapi] (version 1.7.0 or higher) compatible
servers.

## Help wanted

We currently don't have that much time to spend developing Ultrasonic, so any
contributions or active developers are always welcomed.
Have a look at [CONTRIBUTING](docs/CONTRIBUTING.md) to get started.

## Download

App is available to download at following stores:

[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png" alt="Get it on Google Play" height="70">](https://play.google.com/store/apps/details?id=org.moire.ultrasonic)
[<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="70">](https://f-droid.org/packages/org.moire.ultrasonic/)
[<img src="https://ultrasonic.gitlab.io/assets/img/get-it-on-gitlab.png" alt="Get it on GitLab" height="70">](https://gitlab.com/ultrasonic/ultrasonic/-/releases)

**Warning**: All three versions (Google Play, F-Droid and the APKs) are not
compatible (not signed by the same key)! You must uninstall one to install
the other, which will delete all your data.

If you want to use the version downloaded from F-Droid or from GitLab with
**Android Auto**, you must enable Unknown Sources as it is described in
[this wiki page][wikiaa].

## Bugs and issues

First, see if your issue haven’t been yet reported [here][issues], otherwise
open [a new issue][newissue].

## Documentation

See [documentation](docs/).

## Contributing

See [CONTRIBUTING](docs/CONTRIBUTING.md).

## Supported (tested) OpenSubsonic API implementations

- [Tocata][tocata]
- [Navidrome][navidrome]
- [Ampache][ampache]
- [gonic][gonic]
- [Supysonic][supysonic]

Other *OpenSubsonic API* implementations should work as well as long as
they follow API [documentation][subapi].

## License

This software is licensed under the terms of the GNU General Public License
version 3 (GPLv3).

Full text of the license is available in the [LICENSE](LICENSE) file and
[online][gpl3].

[wikiaa]: https://gitlab.com/ultrasonic/ultrasonic/-/wikis/Using-Ultrasonic-with-Android-Auto
[issues]: https://gitlab.com/ultrasonic/ultrasonic/-/issues
[newissue]: https://gitlab.com/ultrasonic/ultrasonic/-/issues/new
[tocata]: https://github.com/ogarcia/tocata
[navidrome]: https://github.com/navidrome/navidrome
[ampache]: https://github.com/ampache/ampache
[gonic]: https://github.com/sentriz/gonic
[supysonic]: https://github.com/spl0k/supysonic
[subapi]: https://opensubsonic.netlify.app
[gpl3]: https://opensource.org/licenses/gpl-3.0.html
