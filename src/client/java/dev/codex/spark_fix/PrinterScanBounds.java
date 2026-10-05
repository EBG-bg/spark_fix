package dev.codex.spark_fix;

/** Marks an empty scan intersection before the printer normalizes its corner coordinates. */
public interface PrinterScanBounds {
    void sparkFix$markEmptyScan();
}
