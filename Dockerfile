FROM ***REMOVED***:latest


COPY run.sh /run.sh

COPY target/ordnungsamt.jar ordnungsamt.jar

RUN chmod +r /ordnungsamt.jar
RUN chmod +x /run.sh
