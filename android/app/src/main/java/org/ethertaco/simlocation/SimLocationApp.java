// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;
import android.app.Application;
public final class SimLocationApp extends Application {
    @Override public void onCreate() { super.onCreate(); BackendController.initialize(this); }
}
