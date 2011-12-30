.. image:: http://www.bdoubliees.com/journalspirou/sfigures6/schtroumpfs/s3.jpg

Xitrum (means "Smurf" in Vietnamese) is an async and clustered Scala web framework
and HTTP(S) server on top of `Netty <http://www.jboss.org/netty>`_
and `Hazelcast <http://www.hazelcast.com/>`_:

* It tries to fill the spectrum between `Scalatra <https://github.com/scalatra/scalatra>`_
  and `Lift <http://liftweb.net/>`_: more powerful than Scalatra and easier to
  use than Lift. You can easily create both RESTful APIs and postbacks. Xitrum
  is controller-first like Scalatra, not
  `view-first <http://www.assembla.com/wiki/show/liftweb/View_First>`_ like Lift.
* Annotations are used for URL routes, in the spirit of JAX-RS and Rails Engines.
  You don't have to declare all routes in a single place. Think of annotations
  as distributed routes.You can plug an app into another app. If you have a
  blog engine, you can package it as a JAR file. Then you can plug that JAR file
  into another app.
* Typesafe, in the spirit of Scala. All the APIs try to be as typesafe as possible.
* Async, in the spirit of Netty. Your request proccessing action does not have
  to respond immediately. Chunked response (streaming), WebSocket, and Comet
  (using WebSocket or long-polling) are supported.
* Sessions can be stored in cookies (more scalable) or clustered Hazelcast (more secure).
  Hazelcast is recommended when using continuations-based Actions, since serialized
  continuations are usually too big to store in cookies.
* `jQuery Validation <http://docs.jquery.com/Plugins/validation>`_ is integrated
  for browser side and server side validation.
* i18n using `GNU gettext <http://en.wikipedia.org/wiki/GNU_gettext>`_.
  Translation text extraction is done automatically.
  You can use powerful tools like `Poedit <http://www.poedit.net/screenshots.php>`_
  for translating and merging translations.
  gettext is unlike most other solutions, both singular and plural forms are supported.
* Extensive caching for faster responding.
  At the web server layer, small files are cached in memory, big files are sent
  using NIO's zero copy. `All Google's best practices <http://code.google.com/speed/page-speed/docs/rules_intro.html>`_
  like conditional GET are applied.
  At the web framework layer you have can declare page, action, and object cache
  in the Rails style.

Hazelcast also gives:

* In-process and distribued cache, you don't need separate cache servers.
* In-process and distribued `Comet (with WebSocket) <http://en.wikipedia.org/wiki/Comet_(programming)>`_,
  you can scale Comet to multiple web servers.

::

  +------------------+
  |     Your app     |
  +------------------+
  |      Xitrum      |  <-- Hazelcast --> Other instances
  | +--------------+ |
  | | Action/View  | |
  | +--------------+ |
  +------------------+
  |       Netty      |
  | +--------------+ |
  | |HTTP(S) Server| |
  | +--------------+ |
  +------------------+

Xitrum is `open source <https://github.com/ngocdaothanh/xitrum>`_, please join
its `Google group <http://groups.google.com/group/xitrum-framework>`_.

Quick start
-----------

::

  $ git clone https://github.com/ngocdaothanh/xitrum-quickstart.git
  $ cd xitrum-quickstart
  $ sbt/sbt run

Now you have a sample project running at http://localhost:8000/
and https://localhost:4430/

After playing with the quickstart project, please read
`full documentation <http://ngocdaothanh.github.com/xitrum>`_ for details.

Samples
-------

* `Quickstart <https://github.com/ngocdaothanh/xitrum-quickstart>`_
* `Comy <https://github.com/ngocdaothanh/comy>`_
