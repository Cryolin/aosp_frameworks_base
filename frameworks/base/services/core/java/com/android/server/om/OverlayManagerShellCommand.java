/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.om;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.util.TypedValue;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementation of 'cmd overlay' commands.
 *
 * This class provides an interface to the OverlayManagerService via adb.
 * Intended only for manual debugging. Execute 'adb exec-out cmd overlay help'
 * for a list of available commands.
 */
final class OverlayManagerShellCommand extends ShellCommand {
    private final Context mContext;
    private final IOverlayManager mInterface;

    OverlayManagerShellCommand(@NonNull final Context ctx, @NonNull final IOverlayManager iom) {
        mContext = ctx;
        mInterface = iom;
    }

    @Override
    public int onCommand(@Nullable final String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter err = getErrPrintWriter();
        try {
            switch (cmd) {
                case "list":
                    return runList();
                case "enable":
                    return runEnableDisable(true);
                case "disable":
                    return runEnableDisable(false);
                case "enable-exclusive":
                    return runEnableExclusive();
                case "set-priority":
                    return runSetPriority();
                case "lookup":
                    return runLookup();
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
        } catch (RemoteException e) {
            err.println("Remote exception: " + e);
        }
        return -1;
    }

    @Override
    public void onHelp() {
        final PrintWriter out = getOutPrintWriter();
        out.println("Overlay manager (overlay) commands:");
        out.println("  help");
        out.println("    Print this help text.");
        out.println("  dump [--verbose] [--user USER_ID] [[FIELD] PACKAGE]");
        out.println("    Print debugging information about the overlay manager.");
        out.println("    With optional parameter PACKAGE, limit output to the specified");
        out.println("    package. With optional parameter FIELD, limit output to");
        out.println("    the value of that SettingsItem field. Field names are");
        out.println("    case insensitive and out.println the m prefix can be omitted,");
        out.println("    so the following are equivalent: mState, mstate, State, state.");
        out.println("  list [--user USER_ID] [PACKAGE]");
        out.println("    Print information about target and overlay packages.");
        out.println("    Overlay packages are printed in priority order. With optional");
        out.println("    parameter PACKAGE, limit output to the specified package.");
        out.println("  enable [--user USER_ID] PACKAGE");
        out.println("    Enable overlay package PACKAGE.");
        out.println("  disable [--user USER_ID] PACKAGE");
        out.println("    Disable overlay package PACKAGE.");
        out.println("  enable-exclusive [--user USER_ID] [--category] PACKAGE");
        out.println("    Enable overlay package PACKAGE and disable all other overlays for");
        out.println("    its target package. If the --category option is given, only disables");
        out.println("    other overlays in the same category.");
        out.println("  set-priority [--user USER_ID] PACKAGE PARENT|lowest|highest");
        out.println("    Change the priority of the overlay PACKAGE to be just higher than");
        out.println("    the priority of PACKAGE_PARENT If PARENT is the special keyword");
        out.println("    'lowest', change priority of PACKAGE to the lowest priority.");
        out.println("    If PARENT is the special keyword 'highest', change priority of");
        out.println("    PACKAGE to the highest priority.");
        out.println("  lookup [--user USER_ID] [--verbose] PACKAGE-TO-LOAD PACKAGE:TYPE/NAME");
        out.println("    Load a package and print the value of a given resource");
        out.println("    applying the current configuration and enabled overlays.");
        out.println("    For a more fine-grained alernative, use 'idmap2 lookup'.");
    }

    private int runList() throws RemoteException {
        final PrintWriter out = getOutPrintWriter();
        final PrintWriter err = getErrPrintWriter();

        int userId = UserHandle.USER_SYSTEM;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                default:
                    err.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final String packageName = getNextArg();
        if (packageName != null) {
            List<OverlayInfo> overlaysForTarget = mInterface.getOverlayInfosForTarget(
                    packageName, userId);

            // If the package is not targeted by any overlays, check if the package is an overlay.
            if (overlaysForTarget.isEmpty()) {
                final OverlayInfo info = mInterface.getOverlayInfo(packageName, userId);
                if (info != null) {
                    printListOverlay(out, info);
                }
                return 0;
            }

            out.println(packageName);

            // Print the overlays for the target.
            final int n = overlaysForTarget.size();
            for (int i = 0; i < n; i++) {
                printListOverlay(out, overlaysForTarget.get(i));
            }

            return 0;
        }

        // Print all overlays grouped by target package name.
        final Map<String, List<OverlayInfo>> allOverlays = mInterface.getAllOverlays(userId);
        for (final String targetPackageName : allOverlays.keySet()) {
            out.println(targetPackageName);

            List<OverlayInfo> overlaysForTarget = allOverlays.get(targetPackageName);
            final int n = overlaysForTarget.size();
            for (int i = 0; i < n; i++) {
                printListOverlay(out, overlaysForTarget.get(i));
            }
            out.println();
        }

        return 0;
    }

    private void printListOverlay(PrintWriter out, OverlayInfo oi) {
        String status;
        switch (oi.state) {
            case OverlayInfo.STATE_ENABLED_IMMUTABLE:
            case OverlayInfo.STATE_ENABLED:
                status = "[x]";
                break;
            case OverlayInfo.STATE_DISABLED:
                status = "[ ]";
                break;
            default:
                status = "---";
                break;
        }
        out.println(String.format("%s %s", status, oi.packageName));
    }

    private int runEnableDisable(final boolean enable) throws RemoteException {
        final PrintWriter err = getErrPrintWriter();

        int userId = UserHandle.USER_SYSTEM;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                default:
                    err.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final String packageName = getNextArgRequired();
        return mInterface.setEnabled(packageName, enable, userId) ? 0 : 1;
    }

    private int runEnableExclusive() throws RemoteException {
        final PrintWriter err = getErrPrintWriter();

        int userId = UserHandle.USER_SYSTEM;
        boolean inCategory = false;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                case "--category":
                    inCategory = true;
                    break;
                default:
                    err.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }
        final String overlay = getNextArgRequired();
        if (inCategory) {
            return mInterface.setEnabledExclusiveInCategory(overlay, userId) ? 0 : 1;
        } else {
            return mInterface.setEnabledExclusive(overlay, true, userId) ? 0 : 1;
        }
    }

    private int runSetPriority() throws RemoteException {
        final PrintWriter err = getErrPrintWriter();

        int userId = UserHandle.USER_SYSTEM;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                default:
                    err.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final String packageName = getNextArgRequired();
        final String newParentPackageName = getNextArgRequired();

        if ("highest".equals(newParentPackageName)) {
            return mInterface.setHighestPriority(packageName, userId) ? 0 : 1;
        } else if ("lowest".equals(newParentPackageName)) {
            return mInterface.setLowestPriority(packageName, userId) ? 0 : 1;
        } else {
            return mInterface.setPriority(packageName, newParentPackageName, userId) ? 0 : 1;
        }
    }

    private int runLookup() throws RemoteException {
        final PrintWriter out = getOutPrintWriter();
        final PrintWriter err = getErrPrintWriter();

        int userId = UserHandle.USER_SYSTEM;
        boolean verbose = false;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                case "--verbose":
                    verbose = true;
                    break;
                default:
                    err.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final String packageToLoad = getNextArgRequired();

        final String fullyQualifiedResourceName = getNextArgRequired(); // package:type/name
        final Pattern regex = Pattern.compile("(.*?):(.*?)/(.*?)");
        final Matcher matcher = regex.matcher(fullyQualifiedResourceName);
        if (!matcher.matches()) {
            err.println("Error: bad resource name, doesn't match package:type/name");
            return 1;
        }

        final Resources res;
        try {
            res = mContext
                .createContextAsUser(UserHandle.of(userId), /* flags */ 0)
                .getPackageManager()
                .getResourcesForApplication(packageToLoad);
        } catch (PackageManager.NameNotFoundException e) {
            err.println(String.format("Error: failed to get resources for package %s for user %d",
                    packageToLoad, userId));
            return 1;
        }
        final AssetManager assets = res.getAssets();
        try {
            assets.setResourceResolutionLoggingEnabled(true);

            // first try as non-complex type ...
            try {
                final TypedValue value = new TypedValue();
                res.getValue(fullyQualifiedResourceName, value, false /* resolveRefs */);
                final CharSequence valueString = value.coerceToString();
                final String resolution = assets.getLastResourceResolution();

                res.getValue(fullyQualifiedResourceName, value, true /* resolveRefs */);
                final CharSequence resolvedString = value.coerceToString();

                if (verbose) {
                    out.println(resolution);
                }

                if (valueString.equals(resolvedString)) {
                    out.println(valueString);
                } else {
                    out.println(valueString + " -> " + resolvedString);
                }
                return 0;
            } catch (Resources.NotFoundException e) {
                // this is ok, resource could still be a complex type
            }

            // ... then try as complex type
            try {

                final String pkg = matcher.group(1);
                final String type = matcher.group(2);
                final String name = matcher.group(3);
                final int resid = res.getIdentifier(name, type, pkg);
                if (resid == 0) {
                    throw new Resources.NotFoundException();
                }
                final TypedArray array = res.obtainTypedArray(resid);
                if (verbose) {
                    out.println(assets.getLastResourceResolution());
                }
                TypedValue tv = new TypedValue();
                for (int i = 0; i < array.length(); i++) {
                    array.getValue(i, tv);
                    out.println(tv.coerceToString());
                }
                array.recycle();
                return 0;
            } catch (Resources.NotFoundException e) {
                // give up
                err.println("Error: failed to get the resource " + fullyQualifiedResourceName);
                return 1;
            }
        } finally {
            assets.setResourceResolutionLoggingEnabled(false);
        }
    }
}
