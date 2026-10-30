package org.slf4j.impl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

final class DiagnosticLoggerFactory implements ILoggerFactory {
    private final ConcurrentMap<String, Logger> loggers = new ConcurrentHashMap<>();

    @Override
    public Logger getLogger(String name) {
        if (!name.startsWith("bt.")) return NOPLogger.NOP_LOGGER;
        return loggers.computeIfAbsent(name, DiagnosticLogger::new);
    }
}
