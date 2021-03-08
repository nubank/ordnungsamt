FROM 193814090748.dkr.ecr.us-east-1.amazonaws.com/cicd/clojure-cli-builder:latest


COPY run.sh /run.sh

COPY target/ordnungsamt.jar ordnungsamt.jar

RUN chmod +r /ordnungsamt.jar
RUN chmod +x /run.sh
