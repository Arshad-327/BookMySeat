package com.bookmyseat.event.exception;

public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(Long id) {
        super("Event " + id + " not found");
    }
}
