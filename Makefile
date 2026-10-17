# global service name
SERVICE                 := snomed-sparql

APP_VERSION             := $(shell echo `grep "version =" build.gradle | sed "s/version = //; s/'//g"`)

# Run arguments, e.g. make run ARGS="--owl=src/test/data/snomed-inferred-20261001.owl"
ARGS                    ?=

# SNOMED release used for the unit test data (override with make test-data RELEASE_DIR=... VERSION=...)
VERSION                 ?= 20261001
RELEASE_DIR             ?= SnomedCT_InternationalRF2_PRODUCTION_$(VERSION)T120000Z
TEST_OWL                := src/test/data/snomed-inferred-$(VERSION).owl

# OWL file served by "make serve"
OWL                     ?= $(TEST_OWL)

.PHONY: build

# Clean build artifacts
clean:
	./gradlew clean

# Build without tests
build:
	./gradlew build -x test -x spotbugsMain -x spotbugsTest -x javadoc

# Run tests along with spotbugs (checkstyle runs as part of check)
test:
	./gradlew test spotbugsMain spotbugsTest checkstyleMain checkstyleTest

# Install to the local maven repository
install:
	./gradlew publishToMavenLocal -x test -x spotbugsMain -x spotbugsTest -x javadoc

# Run the application, pass mode via ARGS
run:
	./gradlew bootRun --args="$(ARGS)"

# Serve the ECL REST endpoint on port 8080 for an OWL file (default: the unit test OWL)
serve:
	./gradlew bootJar
	java -Xmx16g -jar build/libs/$(SERVICE)-$(APP_VERSION)-exec.jar --owl=$(OWL)

# Regenerate the inferred OWL file used by the unit tests from the RF2 release
test-data:
	./gradlew bootJar
	java -Xmx20g -jar build/libs/$(SERVICE)-$(APP_VERSION)-exec.jar --release=$(RELEASE_DIR) --output=$(TEST_OWL)

# Local Snowstorm used to generate expected ECL results (see snowstorm/README.md)
SNOWSTORM_COMPOSE       := docker compose -f snowstorm/docker-compose.yml
SNOWSTORM_URL           ?= http://127.0.0.1:8081
RELEASE_ZIP             := snowstorm/import/$(RELEASE_DIR).zip

snowstorm-up:
	$(SNOWSTORM_COMPOSE) up -d
	@until curl -sf $(SNOWSTORM_URL)/version >/dev/null; do sleep 5; done; curl -s $(SNOWSTORM_URL)/version

snowstorm-down:
	$(SNOWSTORM_COMPOSE) down

# Import the RF2 snapshot into Snowstorm, creating the MAIN/<yyyy-mm-dd> version branch (takes ~30-60 min)
snowstorm-import:
	mkdir -p snowstorm/import
	cd $(dir $(RELEASE_DIR)). && zip -qr $(CURDIR)/$(RELEASE_ZIP) $(notdir $(RELEASE_DIR))/Snapshot
	@id=$$(curl -s -i -X POST $(SNOWSTORM_URL)/imports -H 'Content-Type: application/json' \
	  -d '{"type":"SNAPSHOT","branchPath":"MAIN","createCodeSystemVersion":true}' \
	  | grep -i '^location:' | tr -d '\r' | sed 's#.*/##'); \
	echo "import $$id"; curl -sf -X POST $(SNOWSTORM_URL)/imports/$$id/archive -F "file=@$(RELEASE_ZIP)"; \
	while ! curl -s $(SNOWSTORM_URL)/imports/$$id | grep -qE '"status" : "(COMPLETED|FAILED)"'; do sleep 60; done; \
	curl -s $(SNOWSTORM_URL)/imports/$$id

# Regenerate the expected ECL results (src/test/resources/data/$(VERSION)) from the local Snowstorm
expected-results:
	./gradlew bootJar
	java -jar build/libs/$(SERVICE)-$(APP_VERSION)-exec.jar --fetch-examples=$(VERSION) --snowstorm-url=$(SNOWSTORM_URL)

version:
	@echo $(APP_VERSION)
