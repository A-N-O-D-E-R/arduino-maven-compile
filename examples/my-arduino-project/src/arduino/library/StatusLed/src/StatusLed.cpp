#include "StatusLed.h"

StatusLed::StatusLed(uint8_t pin)
    : _pin(pin), _state(false), _lastToggle(0) {}

void StatusLed::begin() {
    pinMode(_pin, OUTPUT);
    digitalWrite(_pin, LOW);
}

void StatusLed::blink(unsigned long intervalMs) {
    unsigned long now = millis();
    if (now - _lastToggle >= intervalMs) {
        _state = !_state;
        digitalWrite(_pin, _state ? HIGH : LOW);
        _lastToggle = now;
    }
}
