# Makefile — anland-shell build convenience
#
# Environment:
#   JAVA_HOME  JDK root (keytool; its bin is prepended to PATH)
#
# Usage:
#   make            # build/anland-shell.apk (release, debug-signed)
#   make clean
SHELL := /bin/bash
.ONESHELL:
.SHELLFLAGS := -eu -o pipefail -c

ifneq ($(JAVA_HOME),)
PATH := $(JAVA_HOME)/bin:$(PATH)
export PATH
endif

OUT := build

.PHONY: apk clean

apk:
	if [ ! -f debug.keystore ]; then
	  keytool -genkeypair -keystore debug.keystore -alias anland \
	    -storepass anland -keypass anland -keyalg RSA -keysize 2048 \
	    -validity 10000 -dname "CN=Anland Shell Debug" >/dev/null 2>&1
	fi
	./gradlew --no-daemon -q assembleRelease
	mkdir -p "$(OUT)"
	cp -f build/outputs/apk/release/anland-shell-release.apk "$(OUT)/anland-shell.apk"
	ls -la "$(OUT)/anland-shell.apk"
	echo "OK: $(OUT)/anland-shell.apk"

clean:
	rm -rf build/ .gradle/
