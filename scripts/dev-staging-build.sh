#!/bin/bash
# stages build for dev.speck.ly

echo ":: building ui code ..."
lein clean && lein cljsbuild once min

echo ":: staging ..."

CWD=`pwd`
DEST=$CWD/dev.speck.ly
RESOURCES=$CWD/resources/public

if [ -d "$DEST" ] ; then
    echo ":: cleaning existing directory: $DEST ..."
    rm -rf $DEST
fi

# copy files
#
mkdir -p $DEST/{js,css,img}

cp -v $RESOURCES/index.dev.speckly.html $DEST/index.html
cp -v $RESOURCES/resources.json $DEST/resources.json
cp -v $RESOURCES/filters.json $DEST/filters.json
cp -v $RESOURCES/js/compiled/plasio_ui.js $DEST/plasio-ui.js
cp -v $RESOURCES/lib/dist/plasio.js $DEST/js/plasio.js
cp -v $RESOURCES/js/plasio-renderer.cljs.js $DEST/js/plasio-renderer.cljs.js
cp -v $RESOURCES/img/entwine.png $DEST/img/entwine.png
cp -v $RESOURCES/css/style.css $DEST/css/style.css

echo ":: done."
