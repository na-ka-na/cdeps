cdeps
=====

Generate namespace or package dependency graphs for Clojure source.

The graph is available in many forms - Clojure map, .xml, .dot, .png

Try it
=======

To begin clone the repo. Then:

 ../cdeps$ lein deps
 ../cdeps$ java -cp lib/clojure-1.2.0.jar:lib/clojure-contrib-1.2.0.jar:src clojure.main
 Clojure 1.2.0
 user=> (require 'cdeps)
 nil
 user=> (def n (cdeps/get-ns-deps "src"))
 #'user/n
 user=> (cdeps/show-deps-as-image n)
 Generating xml file tmp/deps1998007352610584369.xml ...
 Generating dot file tmp/deps6773090530659788743.dot ...
 Generating image-file tmp/deps9029791036451121758.png ...
 "tmp/deps9029791036451121758.png"
 user=> (def n (cdeps/get-ns-deps "../compojure/src"))
 #'user/n
 user=> (cdeps/show-deps-as-image n)
 Generating xml file tmp/deps736822425291793699.xml ...
 Generating dot file tmp/deps5214394967951933127.dot ...
 Generating image-file tmp/deps4613943607332204272.png ...
 "tmp/deps4613943607332204272.png"
 user=> 

Documentation
=============

For more documentation see autodoc/index.html

For examples see examples/ directory
