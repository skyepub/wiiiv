rootProject.name = "wiiiv"

include("wiiiv-core")
project(":wiiiv-core").projectDir = file("wiiiv-backend/wiiiv-core")

include("wiiiv-server")
project(":wiiiv-server").projectDir = file("wiiiv-backend/wiiiv-server")

include("wiiiv-cli")

include("wiiiv-plugin-webhook")
project(":wiiiv-plugin-webhook").projectDir = file("wiiiv-plugins/wiiiv-plugin-webhook")

include("wiiiv-plugin-cron")
project(":wiiiv-plugin-cron").projectDir = file("wiiiv-plugins/wiiiv-plugin-cron")

include("wiiiv-plugin-mail")
project(":wiiiv-plugin-mail").projectDir = file("wiiiv-plugins/wiiiv-plugin-mail")

include("wiiiv-plugin-webfetch")
project(":wiiiv-plugin-webfetch").projectDir = file("wiiiv-plugins/wiiiv-plugin-webfetch")

include("wiiiv-plugin-spreadsheet")
project(":wiiiv-plugin-spreadsheet").projectDir = file("wiiiv-plugins/wiiiv-plugin-spreadsheet")

include("wiiiv-plugin-pdf")
project(":wiiiv-plugin-pdf").projectDir = file("wiiiv-plugins/wiiiv-plugin-pdf")
