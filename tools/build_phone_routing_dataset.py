#!/usr/bin/env python3
"""Build a phone-routing training dataset in typed-decisions format.

Produces train/calibration/test JSONL files for fine-tuning the Clef
decision head on phone-specific routing. The 9-route schema exactly matches
what is used in benchmark_decision_android.py and in the phone app.

Output: data/phone_routing/
  train.jsonl         ~900 records (100/route)
  calibration.jsonl   ~90 records  (10/route)
  test.jsonl          ~90 records  (10/route) ← hold-out; do not train on this

Design notes:
- Hard labels (confidence=1.0, probs are one-hot). Soft labels from annotation
  would be better but require a labeller; hard labels are appropriate for
  synthetically generated clear-signal examples.
- phone_health_check = multi-category OR overall condition queries. Single
  category queries with health language (e.g. "is my battery healthy?")
  are single-category tools, not phone_health_check.
- The 50 development cases in tools/replay_agent_pilot.py are excluded from
  all splits (dev set must not contaminate training or evaluation).
"""
from __future__ import annotations

import hashlib
import json
import random
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).parent.parent
OUT_DIR = REPO_ROOT / "data" / "phone_routing"
sys.path.insert(0, str(REPO_ROOT / "tools"))

QUESTIONS_SCHEMA = {
    "route": {
        "type": "choice",
        "instructions": (
            "Select exactly one Android agent route by the meaning of the request. "
            "Use live phone tools only for current phone facts. "
            "Use phone_health_check for overall condition or two or more live categories. "
            "Otherwise answer without a tool."
        ),
        "criteria": {
            "answer": "Writing, jokes, arithmetic, definitions, general knowledge, or unclear requests. No live phone data needed.",
            "get_device_info": "Current phone model, manufacturer, Android version, CPU ABI or physical RAM.",
            "get_battery_info": "Live battery level, charging status, temperature, heat or need to cool down.",
            "get_storage_info": "Live internal disk space, used/free/total storage, capacity to save files or models.",
            "get_media_info": "Currently playing song or video metadata.",
            "search_local_files": "Find a filename or phrase in previously authorized local files.",
            "search_notes": "Recall or find information previously saved to notes.",
            "save_note": "Explicitly remember or save information for later.",
            "phone_health_check": "Overall phone health, condition, AI workload readiness, check everything, or two or more live categories together.",
        },
    }
}

ALL_ROUTES = list(QUESTIONS_SCHEMA["route"]["criteria"].keys())
QUESTIONS_STR = json.dumps(QUESTIONS_SCHEMA)


def gold(route: str) -> str:
    probs = {r: 0.0 for r in ALL_ROUTES}
    probs[route] = 1.0
    return json.dumps({
        "route": {
            "type": "choice",
            "label": route,
            "confidence": 1.0,
            "probabilities": probs,
        }
    })


# ─── raw query lists per route ───────────────────────────────────────────────
# These were authored here and do NOT overlap with the 50 development cases
# in replay_agent_pilot.py. Spot-checked against those cases before finalising.
# Each list has 150+ entries so splits are deterministic at 100/10/10 even
# after dev-overlap exclusion.

_BASE_QUERIES: dict[str, list[str]] = {

"answer": [
    # general knowledge
    "What is the capital of Germany?",
    "Who wrote Romeo and Juliet?",
    "What year did World War II end?",
    "What is the speed of light?",
    "How many planets are in the solar system?",
    "What is the largest ocean on Earth?",
    "Who invented the telephone?",
    "What is photosynthesis?",
    "What is the chemical symbol for water?",
    "When was the Eiffel Tower built?",
    "What country has the most people?",
    "What is the longest river in the world?",
    "Who painted the Mona Lisa?",
    "What is the smallest country in the world?",
    "How far is the Moon from Earth?",
    # math
    "What is 15 percent of 240?",
    "Calculate the square root of 144",
    "What is 7 multiplied by 8?",
    "Convert 100 Celsius to Fahrenheit",
    "What is 2 to the power of 10?",
    "If I have 50 apples and give away 18, how many remain?",
    "What is the area of a circle with radius 5?",
    "Divide 864 by 12",
    "What is 35 percent of 80?",
    "How many seconds in a day?",
    # writing
    "Write a short haiku about rain",
    "Help me draft a birthday message for my friend",
    "Suggest a subject line for a job application email",
    "Write a brief apology message",
    "Give me a one-line joke about programming",
    "Help me write a thank-you note",
    "Compose a short out-of-office reply",
    "Suggest a toast for a wedding",
    "Write a headline for a coffee shop",
    "Help me rephrase this sentence more formally",
    # definitions / explanations
    "What does HTTP stand for?",
    "Explain what a for loop does",
    "What is machine learning?",
    "Define the term 'algorithm'",
    "What is the difference between RAM and storage?",
    "Explain what Wi-Fi 6 is",
    "What does DNS stand for?",
    "What is a pixel?",
    "Explain what an API is",
    "What is Bluetooth?",
    # recommendations
    "What is a good book to read this summer?",
    "Recommend a classic movie",
    "What are some tips for better sleep?",
    "Suggest a healthy breakfast idea",
    "What is a good way to learn a new language?",
    "Give me productivity tips for working from home",
    "What should I pack for a camping trip?",
    "Recommend a board game for two players",
    "What is a simple recipe for pasta?",
    "Give me a morning stretching routine",
    # conversational / unclear
    "Hello, how are you?",
    "What can you help me with?",
    "Tell me something interesting",
    "I'm bored, entertain me",
    "What is the meaning of life?",
    "How was your day?",
    "Can you speak Turkish?",
    "Do you know any riddles?",
    "What time is it?",
    "Why is the sky blue?",
    # translation / language
    "How do you say 'thank you' in Japanese?",
    "Translate 'good morning' to Spanish",
    "What does 'bonjour' mean?",
    "How do you pronounce 'Worcestershire'?",
    "What language is spoken in Brazil?",
    # additional general
    "What are the primary colors?",
    "How do airplanes fly?",
    "What is gravity?",
    "Explain how rainbows form",
    "What is a black hole?",
    "How does a vaccine work?",
    "What is the immune system?",
    "What is DNA?",
    "Who was Albert Einstein?",
    "What is inflation in economics?",
    "How does a compass work?",
    "What is the Pythagorean theorem?",
    "Explain supply and demand",
    "What is the stock market?",
    "How do I tie a tie?",
    "What is a haiku?",
    "What are the rules of chess?",
    "Explain the water cycle",
    "What is a metaphor?",
    "What is the difference between a virus and a bacterium?",
    "How does the internet work?",
    "What is a solar eclipse?",
    "What is a prime number?",
    "How do I convert miles to kilometers?",
    "What is the difference between sympathy and empathy?",
    "What year was the iPhone first released?",
    "How many continents are there?",
    "What is a haiku?",
    "What is a calorie?",
    "Explain what a megapixel is",
    "What is blockchain?",
    "Explain what a podcast is",
    "Who is Sherlock Holmes?",
    "What is the tallest mountain on Earth?",
    "How do I calculate BMI?",
],

"get_battery_info": [
    # current level
    "What is my battery percentage right now?",
    "How much battery life do I have left?",
    "What's my current charge level?",
    "Is my battery almost dead?",
    "How much charge do I have?",
    "Is my battery above 50 percent?",
    "Am I running low on battery?",
    "What percentage is my battery at?",
    "Do I have enough battery to last an hour?",
    "Is my phone about to die?",
    # charging status
    "Is my phone charging right now?",
    "Am I plugged in?",
    "Is the charger connected?",
    "Is my phone on charge?",
    "Has my phone finished charging?",
    "Is my wireless charger working?",
    "Is charging active?",
    "Is my phone currently being charged?",
    "Am I on USB or wireless charging?",
    "Did my fast charger kick in?",
    # temperature
    "Is my battery getting hot?",
    "Is the battery temperature normal?",
    "How warm is my battery?",
    "Is my phone overheating?",
    "Is the battery cool enough?",
    "Is my phone running hot?",
    "Is the battery temperature safe?",
    "Is my phone too warm for charging?",
    "How hot is the battery right now?",
    "Is the battery temperature within safe range?",
    # time estimates
    "How long until my phone is fully charged?",
    "How long will my battery last at this rate?",
    "Will my battery last until tonight?",
    "Can I last another two hours on this battery?",
    "How many minutes until the phone dies?",
    # health language — still single-category battery
    "Is my battery in good health?",
    "Is my battery still healthy?",
    "Has my battery degraded?",
    "What is my battery health percentage?",
    "Is my battery capacity still full?",
    "Is my battery wearing out?",
    "How is my battery holding up?",
    "Is my battery performance good?",
    "Should I be worried about my battery condition?",
    "Is my battery getting worse over time?",
    # usage
    "What is draining my battery?",
    "Is something using a lot of battery?",
    "What is my current battery drain rate?",
    "Is background activity hurting my battery?",
    "Is screen-on time reducing my battery?",
    # combined phrasing that is still single-category
    "Check my battery level and let me know if I should charge",
    "Tell me the battery status",
    "Give me the battery info",
    "I need to know my battery",
    "Show me my battery",
    "Battery status please",
    "How is my battery doing?",
    "What does my battery look like?",
    "Is my battery okay right now?",
    "Battery check — how much do I have?",
    "Let me know about my battery",
    "Battery report please",
    "How is the battery performing?",
    "Is my battery going to last for the meeting?",
    "Will my phone survive a two-hour drive?",
    "Can I watch a movie on this battery?",
    "Do I need to charge before I leave?",
    "Will the battery last until I get home?",
    "Can I make a 30-minute call on this battery?",
    "Is my battery life acceptable for today?",
],

"get_device_info": [
    # model and manufacturer
    "What phone model is this?",
    "Who made this phone?",
    "What is the brand of this device?",
    "What is the full model name of my phone?",
    "Is this a Samsung phone?",
    "What exact device am I using?",
    "What is the model number of this phone?",
    "What kind of phone is this?",
    "What device model is running this app?",
    "What is this phone's product name?",
    # Android version
    "What version of Android is installed?",
    "What Android release am I on?",
    "Is my Android up to date?",
    "What is my Android version number?",
    "Am I running Android 13?",
    "What API level is my phone?",
    "What is the Android build version?",
    "Is this Android 12 or 13?",
    "What OS version does this phone have?",
    "What is the current Android SDK version?",
    # RAM
    "How much RAM does this phone have?",
    "What is the total memory of this device?",
    "How many gigabytes of RAM do I have?",
    "Is this a 4GB or 6GB RAM phone?",
    "How much physical memory does this device have?",
    "What is the RAM capacity of my phone?",
    "Does this phone have 8GB of RAM?",
    "What is this device's memory specification?",
    "How much RAM is on this phone?",
    "What is the installed RAM?",
    # CPU / ABI
    "What CPU does this phone use?",
    "What processor is in my phone?",
    "What is the chip inside this phone?",
    "What CPU architecture does this phone support?",
    "Is this an ARM64 device?",
    "What is the ABI of this phone?",
    "What chipset powers this phone?",
    "What is this device's instruction set?",
    "What processor model is in here?",
    "Is this phone running on a Mediatek chip?",
    # hardware specs
    "What are the specs of this phone?",
    "What is the screen resolution of this device?",
    "What display size does this phone have?",
    "Does this phone support 5G?",
    "What sensors does this phone have?",
    "What is the hardware configuration of this device?",
    # health phrasing — still single-category device
    "Is my device model still supported?",
    "Is this phone hardware still current?",
    "What is the device identification?",
    "Tell me about this device",
    "What device am I on?",
    "Device info please",
    "Show me this device's specifications",
    "What Android build is this?",
    "What is my device fingerprint?",
    "Tell me the device details",
    "Describe this phone's hardware",
    "What hardware is in this phone?",
    "What Android release version is installed on this device?",
    "Is this phone 64-bit?",
    "What is the kernel version?",
    "What system is running on this phone?",
    "What is my phone's security patch level?",
    "What build number is installed?",
    "Is this device compatible with ARMv8.2?",
    "What SoC does this phone use?",
],

"get_storage_info": [
    # free space
    "How much storage space do I have left?",
    "How much free space is on my phone?",
    "Am I running out of storage?",
    "How many GB of space do I have free?",
    "Is my storage almost full?",
    "How much room is left on my phone?",
    "Do I have enough space to install an app?",
    "How much storage is available?",
    "Is my internal storage nearly full?",
    "How much space is left on the device?",
    # usage
    "How much storage am I using?",
    "How full is my phone's storage?",
    "What percentage of my storage is used?",
    "How much of my storage is taken up?",
    "What is my storage utilization?",
    # total capacity
    "How much total storage does my phone have?",
    "What is the storage capacity of this phone?",
    "Is this a 64GB or 128GB phone?",
    "How many gigabytes of storage does this device have?",
    "What is the internal storage size?",
    # file downloads and installs
    "Can I download a 500MB app?",
    "Will a 2GB video fit on my phone?",
    "Do I have space for a new game?",
    "Can I save a large PDF?",
    "Is there room to record a 10-minute video?",
    "Can I install this 1.5GB update?",
    "Do I have space for photos from today?",
    "Will I be able to cache this podcast?",
    "Can I download this 300MB model file?",
    "Is there enough space for a 4K video?",
    # model specific
    "Can I run an AI model locally with my storage?",
    "Do I have enough space to download the LLM?",
    "Is there room for a 600MB GGUF file?",
    "Can I fit another model on this phone?",
    "How much storage would I need to free up for a 1GB model?",
    # cleanup
    "Should I free up storage?",
    "Am I low on disk space?",
    "Do I need to delete some files?",
    "Is my storage critically low?",
    "What is my storage status?",
    # combined phrasing — still single category
    "Tell me about my storage",
    "Storage check",
    "How is my storage?",
    "Storage report please",
    "What is my disk space situation?",
    "Show me my storage status",
    "Is my phone's storage okay?",
    "How much of my storage is left?",
    "Storage info",
    "Is my storage healthy?",
    "What are my storage stats?",
    "How is my storage holding up?",
    "How much space is on this phone?",
    "Tell me my storage numbers",
    "Do I have room for more files?",
    "Can I keep more photos?",
    "Is there space for my music library?",
    "How much data am I storing?",
    "Can I save a movie locally?",
    "What is the storage breakdown on this phone?",
],

"get_media_info": [
    # current track
    "What song is playing right now?",
    "What track is on?",
    "What music is playing?",
    "What is the current song?",
    "What is playing on my headphones?",
    "What is this song?",
    "What track am I listening to?",
    "Identify the current song",
    "What song is this?",
    "Which song is currently playing?",
    # album / artist
    "Who is the artist of this song?",
    "What album is this from?",
    "Who sings this?",
    "What band is playing right now?",
    "Who recorded this track?",
    "What is the album name for the current song?",
    "What genre is the current track?",
    "Who is performing this song?",
    "What label released this song?",
    "What year was this song released?",
    # podcast / audio
    "What podcast am I listening to?",
    "What episode is playing?",
    "What podcast episode am I on?",
    "How far into the podcast am I?",
    "What show is this podcast from?",
    "What is the podcast title?",
    "Which episode number is playing?",
    # video
    "What video is playing?",
    "What movie is on right now?",
    "What is the title of the current video?",
    "What show episode am I watching?",
    "What video file is playing?",
    # track details
    "How long is this song?",
    "How much time is left on this track?",
    "Is this song almost over?",
    "What timestamp am I at in this song?",
    "What is the duration of the current track?",
    # player state
    "Is music paused or playing?",
    "Is the player active right now?",
    "Is anything playing on my phone?",
    "Is there audio playing right now?",
    "Is the media player running?",
    "What media app is playing audio?",
    "Is Spotify playing?",
    "Is YouTube Music active?",
    # combined phrasing — still single category
    "Tell me what I'm listening to",
    "Media info please",
    "What is currently playing?",
    "Show me now playing",
    "What's on?",
    "Give me the now-playing info",
    "Media status",
    "What audio is active?",
    "What is the current media?",
    "Identify the media playing",
    "What content is playing on my phone?",
    "What sound is my phone making?",
    "Playing what?",
    "Current track info",
    "What song came on?",
    "This song playing — what is it?",
    "I want to know what's playing",
    "Name the song that's on right now",
    "Show me now-playing metadata",
    "Who is performing what I'm hearing?",
],

"search_local_files": [
    # by filename
    "Find the file named report_final.pdf",
    "Search for a document called budget_2026",
    "Find my file named contract.docx",
    "Look for a PDF I downloaded called user_manual",
    "Find the file project_proposal that I saved last week",
    "Is there a file called invoice_april on my phone?",
    "Locate the file I downloaded from the email",
    "Find my downloaded tax form",
    "Search for a spreadsheet named expenses",
    "Find the presentation file I saved yesterday",
    # by type
    "Find all PDF files on my phone",
    "Search for Word documents I have",
    "Find recently downloaded files",
    "What APK files do I have?",
    "Search for any ZIP archives",
    "Find MP3 files on my phone",
    "Look for image files I downloaded",
    "Find text files on my device",
    "Search for any Excel files",
    "Find video files in my downloads",
    # by content phrase
    "Search my files for the phrase 'meeting notes'",
    "Find a document that mentions 'contract renewal'",
    "Search for files containing 'passport number'",
    "Find a file with the text 'quarterly review'",
    "Look for documents mentioning 'project deadline'",
    "Search my files for 'insurance policy'",
    "Find documents containing 'travel itinerary'",
    "Search for any file with 'recipe' in the content",
    "Find files mentioning 'annual report'",
    "Look for a document that has my address",
    # context
    "Find the photo I took of the whiteboard",
    "Search for the receipt I photographed",
    "Find the screenshot from last Tuesday",
    "Look for photos from my last trip",
    "Find images I saved from the web",
    "Search for documents I worked on this month",
    "Find the file I received from my colleague",
    "Look for anything I downloaded from email",
    "Find the backup file I made",
    "Search for my CV file",
    # combined phrasing — still search_local_files
    "Can you find that file for me?",
    "I'm looking for a specific document",
    "Help me find a file on my phone",
    "Locate a document I saved",
    "Search my phone's files",
    "Find something in my downloads",
    "I saved a file earlier, help me find it",
    "Look through my phone's storage for a document",
    "Find that file I downloaded",
    "Search my local storage",
    "Help me track down a file",
    "I need to find a document on my device",
    "Search my phone for a file",
    "Can you locate a document I have?",
    "Find a locally stored file",
    "Where did I save that PDF?",
    "Find the file I moved to downloads",
    "Look for my saved file",
    "Search for a document on this phone",
    "Find that document I transferred from my computer",
],

"search_notes": [
    # recall specific saved info
    "What phone number did I save in my notes?",
    "What address did I note down?",
    "What was the password I saved?",
    "What meeting time did I record?",
    "What did I note about the appointment?",
    "Find the WiFi password I saved",
    "What was the PIN I wrote down?",
    "What bus route did I note?",
    "What did I save about the doctor?",
    "What note do I have about the flight?",
    # grocery / shopping
    "What is on my grocery list?",
    "What did I put on the shopping list?",
    "What items did I save to buy?",
    "What do I need to buy at the store?",
    "Show me my shopping list note",
    "What was on my list for the supermarket?",
    "What did I note to pick up?",
    "Did I save a grocery list?",
    "What food items did I save to notes?",
    "What was my shopping reminder?",
    # events / appointments
    "When is my dentist appointment?",
    "What time is the meeting I noted?",
    "What did I save about Thursday's event?",
    "What reminder did I set for next week?",
    "What appointment did I write down?",
    "What did I note about the interview?",
    "When did I note the delivery would arrive?",
    "What date did I save for the flight?",
    "What event did I record in notes?",
    "What time did I write down for the call?",
    # ideas / tasks
    "What ideas did I save?",
    "What tasks did I write down?",
    "What did I note as a to-do?",
    "What project ideas did I save?",
    "What brainstorm notes do I have?",
    "What did I jot down this morning?",
    "What did I save as a reminder?",
    "What notes did I write yesterday?",
    "What did I save about the project?",
    "What task did I write down earlier?",
    # general recall
    "What notes have I saved?",
    "Check my saved notes",
    "What information did I save?",
    "What did I put in notes?",
    "Can you find something I saved?",
    "What have I noted?",
    "Look in my notes for that information",
    "Search my saved notes",
    "What is in my notes?",
    "Find something in my notes",
    "What have I written down?",
    "Recall what I saved",
    "Do I have a note about that?",
    "Check if I noted that down",
    "What saved information do I have?",
    "Is there a note about that?",
    "What did I remember to note?",
    "Search my notes for the address",
    "Find the note I saved last week",
    "What is the information I saved earlier?",
],

"save_note": [
    # phone numbers / contact info
    "Save this number: 555-0198",
    "Remember my doctor's number is 0212 555 0101",
    "Note down this phone number: +90 532 123 4567",
    "Keep this contact: emergencyline 112",
    "Save the office number: 444-0900",
    "Remember: plumber is at 555-7823",
    "Note this fax: 212-000-1234",
    "Save this helpline number for me",
    "Jot down this customer service number",
    "Store this phone number in my notes",
    # addresses
    "Save this address: 15 Bağcılar St, Istanbul",
    "Note down: my new office is at 3rd floor, Tower B",
    "Remember: the hotel is at 123 Harbour Road",
    "Save the delivery address: Flat 4, Block 7, Ankara",
    "Note this: meeting venue is Main Conference Room, Level 2",
    "Keep this address on file",
    "Remember this location for later",
    "Store the restaurant address",
    "Note the pickup address for me",
    "Save this shipping address",
    # dates / appointments
    "Note that my dentist appointment is Monday at 3pm",
    "Remember: interview on Friday at 10am",
    "Save this: flight departs at 07:45 on November 3rd",
    "Note my next meeting is Tuesday 2pm",
    "Remember that the event is December 15th",
    "Save the appointment for next Thursday at noon",
    "Note: product launch is on the 25th",
    "Remember my subscription renews in January",
    "Save this: quarterly review is March 10th",
    "Note the deadline: submit by Friday EOD",
    # passwords / codes / PINs
    "Save this WiFi password: HomeNetwork2026",
    "Remember this code: 4821",
    "Note this PIN for later: 7392",
    "Keep this password: mountain@cloud",
    "Save the door code: 1357",
    "Note this access code: A7B2",
    "Remember this serial number: SN-20261003",
    "Store this license key",
    "Save this temporary password",
    "Note this code I need later",
    # ideas / reminders
    "Remember to buy milk on the way home",
    "Note: buy a birthday gift for Ali",
    "Save idea: create a habit tracker app",
    "Remember to call mom this weekend",
    "Note: water the plants every Tuesday",
    "Save this recipe: pasta bolognese — simmer for 40 min",
    "Remember: car service is due next month",
    "Note: charge the power bank tonight",
    "Save this for later: read the quarterly report",
    "Remember to submit the form before Friday",
    # explicit save language
    "Please save what I just said",
    "Note this down for me",
    "Keep a record of this",
    "Write this down so I don't forget",
    "Make a note about this",
    "Remember this information",
    "Save this to my notes",
    "Store this for later",
    "Add this to my notes",
    "Keep this somewhere safe",
    "Save this: my account number is 12345678",
    "Note this measurement: 180cm by 90cm",
    "Remember this URL for later",
    "Save the name: Dr. Ayşe Kaya",
    "Note the order number: ORD-20261003-88912",
    "Remember my tracking number is TRK12345",
    "Save this: team standup is at 9am every day",
    "Note that the API key is abc123xyz",
    "Keep a note of the car plate: 34 ABC 567",
    "Save this information I'll need it later",
],

"phone_health_check": [
    # explicit multi-category queries
    "Check my battery and storage at the same time",
    "How are my battery and device specs doing?",
    "Show me battery, storage, and device info",
    "Check battery level and available storage for me",
    "How much battery and how much space do I have?",
    "Check my device specs and battery together",
    "Is my storage and battery both okay for a road trip?",
    "Check battery and device status",
    "Show me storage capacity and current battery",
    "Check all phone stats: battery, storage, and model info",
    "What is my battery and storage situation?",
    "Give me battery and storage readings",
    "Both battery and storage check please",
    "Tell me battery status and available disk space",
    "Check my phone's battery, storage, and CPU",
    # overall health / condition
    "How is my phone doing overall?",
    "What is the overall health of my phone?",
    "Is my phone in good condition?",
    "Run a full health check on my phone",
    "Check everything on my phone",
    "How is my phone performing overall?",
    "Is my phone healthy?",
    "How is the phone's overall condition?",
    "Give me a complete phone status report",
    "What is my phone's overall performance like?",
    # AI workload readiness
    "Is my phone ready to run an AI model?",
    "Can my phone handle a local LLM workload?",
    "Is the phone ready for intensive computation?",
    "Is my phone in a good state to start a long AI task?",
    "Should I run the model now or wait?",
    "Is my phone ready for a heavy AI workload?",
    "Can my phone handle the inference task right now?",
    "Is everything okay to start a model download and run?",
    "Check if the phone is ready for AI inference",
    "Is my phone prepared for an intensive ML task?",
    # readiness / trip checks
    "Check my phone before I leave for my trip",
    "Is my phone ready for a long flight?",
    "Can my phone last the day? Check everything",
    "Am I ready to go? Quick phone check",
    "Give me a readiness check before the presentation",
    "Check my phone thoroughly before the meeting",
    "Is everything okay with my phone right now?",
    "Do a full check — battery, storage, all of it",
    "Is my phone in shape for a hike with no charging?",
    "Give me a complete status before I head out",
    # 'check everything' phrasing
    "Check everything on my phone and report back",
    "Do a full phone diagnostic",
    "Run a complete check on my device",
    "Tell me everything about my phone's current state",
    "What does my phone look like right now? All stats",
    "Full status report",
    "Give me all the phone stats",
    "Comprehensive phone report",
    "Do a thorough phone check",
    "Check the phone top to bottom",
    # two or more live category signals in same prompt
    "Am I low on storage and is my battery dying?",
    "Is my battery hot and is there space for a download?",
    "Check if I can both call and browse without issues",
    "Is the device model still supported and does it have space?",
    "Check my phone's charging status and available RAM",
    "Is there enough storage and enough battery for today?",
    "Is my phone too warm and running out of space?",
    "Both my battery and storage feel low — check both",
    "How is battery health and remaining storage?",
    "Is my device info current and is storage okay?",
    "Check battery and let me know about storage too",
    "Battery is at 20% and storage might be full — check both",
    "Quick check: battery, storage, and device all good?",
    "I need to know about both battery and free space",
    "Can you check my phone health including battery and disk?",
    "Check device performance: battery level and disk space",
    "Is my device well-charged and with enough storage for tonight?",
    "Phone readiness check: battery and storage status",
    "How are both the battery and available storage?",
    "Full battery and storage report",
],
}

# Merge extra queries
from phone_routing_extra_queries import EXTRA as _EXTRA_QUERIES
QUERIES: dict[str, list[str]] = {
    route: _BASE_QUERIES[route] + _EXTRA_QUERIES.get(route, [])
    for route in _BASE_QUERIES
}


def build_records(route: str, prompts: list[str], split: str, start_idx: int) -> list[dict]:
    q_str = QUESTIONS_STR
    g_str = gold(route)
    probs_str = json.dumps({r: 0.0 if r != route else 1.0 for r in ALL_ROUTES})
    records = []
    for i, prompt in enumerate(prompts):
        rid = f"pr_{route}_{split}_{start_idx + i:03d}"
        records.append({
            "id": rid,
            "workflow": "phone_routing",
            "split": split,
            "state": prompt,
            "questions": q_str,
            "gold": g_str,
            "factors": "{}",
            "n_questions": 1,
            "label_agreement": json.dumps({"route": {"argmax_agree": True, "argmax_majority": route, "total_variation": 0.0}}),
            "route__label": route,
            "route__confidence": 1.0,
            "route__probabilities": probs_str,
        })
    return records


def main():
    rng = random.Random(3407)

    # Verify none of the development cases appear in training data
    import sys
    sys.path.insert(0, str(REPO_ROOT / "tools"))
    try:
        from replay_agent_pilot import AGENT_TEST_CASES
        dev_prompts = {c.prompt.strip().lower() for c in AGENT_TEST_CASES}
        print(f"Loaded {len(dev_prompts)} dev prompts to exclude")
    except ImportError:
        dev_prompts = set()
        print("Warning: could not load dev prompts for exclusion check")

    all_train, all_calib, all_test = [], [], []

    for route, queries in QUERIES.items():
        # Exclude any query that matches a development case
        clean = []
        excluded = 0
        for q in queries:
            if q.strip().lower() in dev_prompts:
                excluded += 1
                print(f"EXCLUDED (dev overlap): [{route}] {q}")
            else:
                clean.append(q)
        if excluded:
            print(f"  {route}: excluded {excluded} dev-overlapping queries")

        rng.shuffle(clean)

        # Ensure we have enough for the split
        if len(clean) < 120:
            print(f"WARNING: {route} only has {len(clean)} clean queries (need 120)")

        train_q  = clean[:100]
        calib_q  = clean[100:110]
        test_q   = clean[110:120]

        all_train += build_records(route, train_q,  "train",       0)
        all_calib += build_records(route, calib_q,  "calibration", 0)
        all_test  += build_records(route, test_q,   "test",        0)

    # Shuffle train and calib (not test — keep it ordered by route for auditing)
    rng.shuffle(all_train)
    rng.shuffle(all_calib)

    OUT_DIR.mkdir(parents=True, exist_ok=True)

    for split_name, records in [("train", all_train), ("calibration", all_calib), ("test", all_test)]:
        out_path = OUT_DIR / f"{split_name}.jsonl"
        with out_path.open("w", encoding="utf-8") as f:
            for rec in records:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        sha = hashlib.sha256(out_path.read_bytes()).hexdigest()
        print(f"Wrote {len(records):4d} records → {out_path}  sha256={sha[:16]}…")

    # Write manifest
    manifest = {
        "dataset": "phone_routing_v1",
        "schema": "typed-decisions-compatible",
        "routes": ALL_ROUTES,
        "questions": QUESTIONS_SCHEMA,
        "splits": {
            "train":       {"records": len(all_train),  "per_route": 100},
            "calibration": {"records": len(all_calib),  "per_route": 10},
            "test":        {"records": len(all_test),   "per_route": 10},
        },
        "label_type": "hard (confidence=1.0)",
        "dev_cases_excluded": True,
        "notes": [
            "phone_health_check covers multi-category queries and overall-health phrasing.",
            "Single-category queries with health language (e.g. 'is my battery healthy?') are single-category tools.",
            "Test split is held-out; do not train on it or use it to tune thresholds.",
        ],
    }
    manifest_path = OUT_DIR / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    print(f"Manifest → {manifest_path}")


if __name__ == "__main__":
    main()
