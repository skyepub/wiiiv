rootProject.name = "wiiiv"

include("wiiiv-core")
project(":wiiiv-core").projectDir = file("wiiiv-backend/wiiiv-core")

include("wiiiv-server")
project(":wiiiv-server").projectDir = file("wiiiv-backend/wiiiv-server")

include("wiiiv-cli")
