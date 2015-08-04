package com.mediatek.recovery;

import java.util.ArrayList;

public abstract class AbstractExceptionParser {

    /**
     * @hide
     */    
    public static final int PARSER_EXCEPTION_MATCH = 0;
    /**
     * @hide
     */
    public static final int PARSER_EXCEPTION_MISMATCH = 1;
    /**
     * @hide
     */
    public static final int PARSER_ERROR = -1;
    protected int lastError = PARSER_EXCEPTION_MISMATCH;

    /**
     * @hide
     */    
    public abstract ArrayList<String> parseException(RuntimeException e);

    /**
     * @hide
     */    
    public int getLastError() {
        return lastError;
    }

    /**
     * @hide
     */    
    public void setLastError(int errorCode) {
        lastError = errorCode;
    }

    /**
     * @hide
     */    
    public boolean scanXML(String xmlPath) {
        return true;
    }

    /**
     * @hide
     */    
    public boolean checkFilePermission(String path) {
        return true;
    }

    /**
     * @hide
     */    
    public static class ParsedException {
        public String mExceptionClassName;
        public String mThrowMethodName;
        public String mThrowClassName;
        
        public ParsedException() {
        }

        public ParsedException(String exceptionClassName, String throwMethodName, String throwClassName) {
            mExceptionClassName = exceptionClassName;
            mThrowMethodName = throwMethodName;
            mThrowClassName = throwClassName;
        }

        /**
         * @hide
         */
        public static ParsedException getNewInstance(Throwable e, Boolean getRootCause) {
            if (e.getStackTrace().length > 0) {
                Throwable rootCause = e;
                if (getRootCause) {
                    while (rootCause.getCause() != null &&
                        rootCause.getCause().getStackTrace().length > 0) {
                        rootCause = rootCause.getCause();
                    }
                }
                StackTraceElement trace = rootCause.getStackTrace()[0];
                String exceptionClassName = rootCause.getClass().getName();
                String throwMethodName = trace.getMethodName();
                String throwClassName = trace.getClassName();
                return new ParsedException(exceptionClassName, throwMethodName, throwClassName);
            } else {
                return null;
            }
        }

        @Override
        public boolean equals (Object other) {
            if (other == null) return false;
            if (other == this) return true;
            if (!(other instanceof ParsedException)) return false;
            ParsedException pe = (ParsedException)other;
            if (pe.mExceptionClassName.equals(mExceptionClassName) &&
                pe.mThrowClassName.equals(mThrowClassName) &&
                pe.mThrowMethodName.equals(mThrowMethodName)) {
                return true;
            } else {
                return false;
            }
        }
    }
}
