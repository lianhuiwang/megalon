MEGALON_BASE=`dirname $0`
source $MEGALON_BASE/conf/conf.sh

java -cp $CP $PROPS org.megalon.multistageserver.TestServer background
