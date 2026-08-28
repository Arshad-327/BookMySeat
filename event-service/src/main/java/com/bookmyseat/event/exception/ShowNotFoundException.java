package com.bookmyseat.event.exception;

public class ShowNotFoundException extends RuntimeException {

    public ShowNotFoundException(Long id) {
        super("Show " + id + " not found");
    }
}
