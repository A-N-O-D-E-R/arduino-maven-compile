#ifndef STATUS_LED_H
#define STATUS_LED_H

#include <Arduino.h>

/**
 * Simple helper that blinks an LED at a configurable interval.
 * Used as a minimal example for library compilation.
 */
class StatusLed {
public:
    StatusLed(uint8_t pin);
    void begin();
    void blink(unsigned long intervalMs);

private:
    uint8_t _pin;
    bool _state;
    unsigned long _lastToggle;
};

#endif
