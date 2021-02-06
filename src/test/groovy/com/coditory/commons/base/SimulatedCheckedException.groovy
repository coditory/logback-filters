package com.coditory.commons.base

class SimulatedCheckedException extends Exception {
    SimulatedCheckedException() {
    }

    SimulatedCheckedException(String message) {
        super(message)
    }

    SimulatedCheckedException(String message, Throwable cause) {
        super(message, cause)
    }
}
