/*
 * BlinkStatus — example sketch for the StatusLed library.
 * Blinks the built-in LED at 500ms intervals.
 */

#include <StatusLed.h>

StatusLed led(LED_BUILTIN);

void setup() {
    led.begin();
}

void loop() {
    led.blink(500);
}
