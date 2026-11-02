# Bandwagon developer tasks. Run `make help` for the list.
#
# Uses the Homebrew Android command-line tools and OpenJDK 17, so no
# Android Studio or shell profile changes are needed.

JAVA_HOME    ?= /opt/homebrew/opt/openjdk@17
FUNCTIONS_JAVA_HOME ?= /opt/homebrew/opt/openjdk@21
ANDROID_HOME ?= /opt/homebrew/share/android-commandlinetools
export JAVA_HOME ANDROID_HOME
export PATH := $(JAVA_HOME)/bin:$(ANDROID_HOME)/emulator:$(ANDROID_HOME)/platform-tools:$(PATH)

API          ?= 35
SYSTEM_IMAGE ?= system-images;android-$(API);google_apis_playstore;arm64-v8a
AVD          ?= bandwagon
DEVICE       ?= pixel_8

# Gradle variant suffix used for app tasks, e.g. DevDebug -> installDevDebug.
VARIANT      ?= DevDebug
# Dev builds use the local Firebase emulators; set to false to use the real bandwagon-dev project.
FIREBASE_EMULATORS ?= true
# Host the app dials for the emulators. 10.0.2.2 = Android emulator; 127.0.0.1 = phone via adb reverse; or a Tailscale IP.
EMULATOR_HOST ?= 10.0.2.2
# Package to launch with `make run`; override if the dev flavor uses a suffix.
APP_ID       ?= fit.bandwagon.dev
SEEDER_ID    ?= fit.bandwagon.tools.hcseeder

# Set HEADLESS=1 to run the emulator without a window (CI, tests).
HEADLESS     ?= 0
EMULATOR_FLAGS = -avd $(AVD) -no-snapshot-save -no-boot-anim -gpu $(if $(filter 1,$(HEADLESS)),swiftshader_indirect -no-window -no-audio,host)
EMULATOR_LOG = /tmp/bandwagon-emulator.log

GRADLEW = ./gradlew
GRADLE = $(GRADLEW) --console=plain -PfirebaseEmulators=$(FIREBASE_EMULATORS) -PemulatorHost=$(EMULATOR_HOST)

# firebase-tools needs JDK 21 first on PATH and comes from functions/node_modules.
FUNCTIONS_ENV = JAVA_HOME=$(FUNCTIONS_JAVA_HOME) PATH="$(FUNCTIONS_JAVA_HOME)/bin:$(CURDIR)/functions/node_modules/.bin:$$PATH"

# Start a package's launcher activity. `monkey` exits with an error on API 35
# images, so resolve the activity and use `am start` instead.
launch = adb shell am start -n "$$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $(1) | tail -1 | tr -d '\r')" >/dev/null

.DEFAULT_GOAL := help
.PHONY: help doctor setup sdk avd emulator emulator-stop emulator-wipe screenshot devices \
        build install run test connected-test lint clean logcat \
        seeder-install seeder-run hc-open fake-member functions-install functions-test functions-rules-test functions-group-test functions-serve \
        marketing-build marketing-serve check-gradlew

help: ## Show this help
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z_-]+:.*## / {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# --- Environment -----------------------------------------------------------

doctor: ## Check that Java, the SDK, adb, the emulator and the AVD are present
	@printf "java       "; java -version 2>&1 | head -1
	@printf "sdkmanager "; command -v sdkmanager >/dev/null && echo ok || echo MISSING
	@printf "adb        "; command -v adb >/dev/null && adb version | head -1 || echo MISSING
	@printf "emulator   "; command -v emulator >/dev/null && emulator -version 2>/dev/null | head -1 || echo "MISSING (run: make sdk)"
	@printf "avd        "; emulator -list-avds 2>/dev/null | grep -qx "$(AVD)" && echo "$(AVD)" || echo "MISSING (run: make avd)"
	@printf "gradlew    "; [ -x $(GRADLEW) ] && echo ok || echo "MISSING (generate the Gradle wrapper)"

setup: sdk avd ## Install SDK packages and create the emulator (one-time)

sdk: ## Install platform, build tools, emulator and system image
	yes | sdkmanager --sdk_root=$(ANDROID_HOME) --licenses >/dev/null
	sdkmanager --sdk_root=$(ANDROID_HOME) "platform-tools" "emulator" \
		"platforms;android-$(API)" "build-tools;$(API).0.0" "$(SYSTEM_IMAGE)"

avd: ## Create the emulator (Android $(API), Health Connect built in)
	@if emulator -list-avds | grep -qx "$(AVD)"; then echo "AVD $(AVD) already exists"; \
	else echo no | avdmanager create avd -n $(AVD) -k "$(SYSTEM_IMAGE)" -d $(DEVICE); fi

# --- Emulator --------------------------------------------------------------

emulator: ## Start the emulator in the background and wait until it has booted
	@if adb devices | grep -q '^emulator-'; then echo "Emulator already running"; exit 0; fi; \
	echo "Starting $(AVD) (log: $(EMULATOR_LOG))"; \
	nohup emulator $(EMULATOR_FLAGS) > $(EMULATOR_LOG) 2>&1 & \
	adb wait-for-device; \
	until [ "$$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done; \
	echo "Emulator booted"

emulator-stop: ## Shut down the running emulator
	-adb emu kill

emulator-wipe: emulator-stop ## Factory-reset the emulator on next start (clears Health Connect data)
	emulator -avd $(AVD) -wipe-data -no-window -no-audio > /dev/null 2>&1 & sleep 15; adb emu kill

screenshot: ## Save the emulator screen to /tmp/screen.png (adb screencap can lag behind on emulators)
	@adb emu screenrecord screenshot /tmp/screen.png >/dev/null && echo /tmp/screen.png

devices: ## List connected devices and emulators
	adb devices -l

# --- App -------------------------------------------------------------------

check-gradlew:
	@[ -x $(GRADLEW) ] || { echo "No ./gradlew yet; generate the Gradle wrapper first."; exit 1; }

build: check-gradlew ## Build the app APK (VARIANT=$(VARIANT))
	$(GRADLE) :app:assemble$(VARIANT)

install: check-gradlew ## Install the app on the running emulator/device
	$(GRADLE) :app:install$(VARIANT)

run: install ## Install and launch the app
	@$(call launch,$(APP_ID))

test: check-gradlew ## Run JVM unit tests for all modules
	$(GRADLE) test

connected-test: check-gradlew ## Run instrumented tests on the running emulator/device
	$(GRADLE) connectedAndroidTest

lint: check-gradlew ## Run Android lint
	$(GRADLE) lint

clean: check-gradlew ## Delete build outputs
	$(GRADLE) clean

logcat: ## Stream logs for the app only
	adb logcat --pid=$$(adb shell pidof -s $(APP_ID) | tr -d '\r')

# --- Health Connect test data ----------------------------------------------

seeder-install: check-gradlew ## Install the hc-seeder debug app (writes fake steps/sleep/exercise)
	$(GRADLE) :tools:hc-seeder:installDebug

seeder-run: seeder-install ## Install and launch hc-seeder
	@$(call launch,$(SEEDER_ID))

hc-open: ## Open Health Connect settings on the emulator
	adb shell am start -a androidx.health.ACTION_HEALTH_CONNECT_SETTINGS

# --- Cloud Functions -------------------------------------------------------

fake-member: ## Add a fake member to a group on the emulators: make fake-member CODE=ABC234 NAME=Arjun [STEPS=6500] [SLEEP=7.5] [BEDTIME=22:45]
	@node tools/dev/fake-member.mjs "$(CODE)" "$(NAME)" $(or $(STEPS),6500) $(SLEEP) $(BEDTIME)

functions-install: ## Install Cloud Functions dependencies
	cd functions && npm install

functions-test: ## Run Cloud Functions tests
	cd functions && npm test

functions-rules-test: ## Run Firestore rules tests (needs JDK 21)
	cd functions && $(FUNCTIONS_ENV) npm run test:rules

functions-group-test: ## Run group integration tests on throwaway emulators (needs JDK 21; safe while functions-serve runs)
	cd functions && $(FUNCTIONS_ENV) npm run test:groups

functions-serve: ## Build Functions and start the Firebase emulators (demo project, no Firebase account needed)
	cd functions && $(FUNCTIONS_ENV) npm run serve

# --- Marketing site (bandwagon.fit) ----------------------------------------

marketing-build: ## Build the bandwagon.fit site into marketing/dist
	marketing/build.sh

marketing-serve: marketing-build ## Build and serve the bandwagon.fit site on http://localhost:4173
	python3 -m http.server 4173 --directory marketing/dist
