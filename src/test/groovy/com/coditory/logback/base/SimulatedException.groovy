package com.coditory.logback.base

class SimulatedException extends RuntimeException {
    SimulatedException() {
    }

    SimulatedException(String message) {
        super(message)
    }

    SimulatedException(String message, Throwable cause) {
        super(message, cause)
    }
}
