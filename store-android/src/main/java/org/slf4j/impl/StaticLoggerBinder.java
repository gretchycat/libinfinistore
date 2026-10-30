package org.slf4j.impl;

import org.slf4j.ILoggerFactory;
import org.slf4j.spi.LoggerFactoryBinder;

/** Routes the BitTorrent library's useful network diagnostics into the app's offloadable log. */
public final class StaticLoggerBinder implements LoggerFactoryBinder {
    public static final String REQUESTED_API_VERSION = "1.7.36";
    private static final StaticLoggerBinder SINGLETON = new StaticLoggerBinder();
    private final ILoggerFactory loggerFactory = new DiagnosticLoggerFactory();

    private StaticLoggerBinder() {}

    public static StaticLoggerBinder getSingleton() {
        return SINGLETON;
    }

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public String getLoggerFactoryClassStr() {
        return DiagnosticLoggerFactory.class.getName();
    }
}
