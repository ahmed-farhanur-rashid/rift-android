package com.rift.di

/**
 * ML layer Hilt module.
 *
 * All ML classes in com.rift.core.ml and com.rift.core.ml.experts use
 * @Singleton + @Inject constructor, so Hilt auto-provides them via
 * constructor injection — no explicit @Provides methods needed.
 *
 * Classes provided automatically:
 *   RssiTracker         — @Singleton, no-arg constructor
 *   OuiLookup           — @Singleton, @ApplicationContext context
 *   EvilTwinDetector    — @Singleton, @ApplicationContext context
 *   RiskScorer          — @Singleton, context + RssiTracker + OuiLookup
 *   InterferencePredictor — @Singleton, @ApplicationContext context
 *   AnomalyDetector     — @Singleton, @ApplicationContext context
 *   MoEGate             — @Singleton, all four experts + RssiTracker + OuiLookup
 *
 * This file is intentionally kept as a doc-only placeholder so the package
 * is clearly the canonical home for ML DI declarations if manual bindings
 * are ever needed (e.g. binding an interface to an implementation).
 */
// No @Module needed — all ML bindings resolved via constructor injection.
