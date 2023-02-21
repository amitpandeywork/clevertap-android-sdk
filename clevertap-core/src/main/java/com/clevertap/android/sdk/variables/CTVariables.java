package com.clevertap.android.sdk.variables;


import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.clevertap.android.sdk.BuildConfig;
import com.clevertap.android.sdk.CleverTapInstanceConfig;
import com.clevertap.android.sdk.Constants;
import com.clevertap.android.sdk.Utils;
import com.clevertap.android.sdk.task.CTExecutors;
import com.clevertap.android.sdk.variables.callbacks.CacheUpdateBlock;
import com.clevertap.android.sdk.variables.callbacks.VariableCallback;
import com.clevertap.android.sdk.variables.callbacks.VariablesChangedCallback;
import java.util.ArrayList;
import java.util.Map;
import org.json.JSONObject;


/**
 * Package Private class to not allow external access to working of Variables
 *
 * @author Ansh Sachdeva
 */
public class CTVariables {

    private boolean variableResponseReceived = false;
    private final ArrayList<VariablesChangedCallback> variablesChangedHandlers = new ArrayList<>();
    private final ArrayList<VariablesChangedCallback> oneTimeVariablesChangedHandlers = new ArrayList<>();
    private final CacheUpdateBlock triggerVariablesChanged = () -> {
        synchronized (variablesChangedHandlers) {
            for (VariablesChangedCallback callback : variablesChangedHandlers) {
                Utils.runOnUiThread(callback);
            }
        }
        synchronized (oneTimeVariablesChangedHandlers) {
            for (VariablesChangedCallback callback : oneTimeVariablesChangedHandlers) {
                Utils.runOnUiThread(callback);
            }
            oneTimeVariablesChangedHandlers.clear();
        }
    };

    private final Context context;
    private final VarCache varCache;

    public CTVariables(final VarCache varCache, final Context context) {
        this.varCache = varCache;
        this.context = context;
    }


    /** WORKING: <br>
     * -2. CleverTapInstanceConfig calls {@link CTVariables()#setVariableContext(Context)} <br><br>
     * -1. User Calls  {@link Parser#parseVariables} or {@link Parser#parseVariablesForClasses} which creates a {@link Var} instance and ends up calling {@link VarCache#registerVariable(Var)} which sets : <br><br>
     *      *** {@link VarCache()#vars} to mapOf(varname -> Var("varname",..) ) <br><br>
     *      *** {@link VarCache()#valuesFromClient}  to mapOf("varNameG"->"varNameL"->value) (check VarCache.registerVariable()) and <br><br>
     *      *** {@link VarCache()#defaultKinds} to mapOf("varname"->varObj.kind) <br><br>
     *  0. user calls this function which triggers calls the following functions synchronously :<br><br>
     *      *** {@link VarCache()#setCacheUpdateBlock(CacheUpdateBlock)} : this sets a callback in {@link VarCache} class, which will be triggered once the values are loaded/updated from the server/cache <br><br>
     *      *** {@link VarCache()#loadDiffs()} : this loads the last cached values of Variables from Shared Preferences, and updates {@link VarCache()#diffs} & {@link VarCache()#merged} accordingly <br><br>
     *  Note that user's callbacks are *not* triggered during init call
     */
    public synchronized void init() {
        varCache.setCacheUpdateBlock(triggerVariablesChanged);
        varCache.loadDiffs(context);
    }

    /**
     * //todo make sure this receives a response from wzrk fetch and app launched
     * originally  <a href="https://github.com/Leanplum/Leanplum-Android-SDK/blob/master/AndroidSDKCore/src/main/java/com/leanplum/Leanplum.java#L843">handleStartResponse()</a><br><br>
     * This function is called once the variable data is available in the response of {@link
     * Constants#APP_LAUNCHED_EVENT}/ {@link Constants#WZRK_FETCH} request  <br><br>
     * -- if the json data is correct we convert json to map and call {@link VarCache#updateDiffsAndTriggerHandlers(Map,Context)}.<br><br>
     * -- else we call {@link VarCache#loadDiffsAndTriggerHandlers(Context)} to set data from cache again
     *
     * @param response JSONObject
     */
    public void handleVariableResponse(@Nullable final JSONObject response) {
        setVariableResponseReceived(true);

        boolean jsonHasVariableData = response != null && true; //check if response was successful, like response.data!=null //todo add logic as per backend response structure
        try {
            if (!jsonHasVariableData) {
                varCache.loadDiffsAndTriggerHandlers(context);
            } else {
                Map<String, Object> variableDiffs = CTVariableUtils.mapFromJson(response.optJSONObject(CTVariableUtils.VARS));
                varCache.updateDiffsAndTriggerHandlers(variableDiffs,context);

            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }


    /**
     * clear current variable data.can be used during profile switch
     */
    public void clearUserContent() {
        varCache.reset();
    }

    public void pushVariablesToServer(Runnable onComplete) {
        varCache.pushVariablesToServer(onComplete);
    }

    public static boolean isInDevelopmentMode() {
        return BuildConfig.DEBUG;
    }

    /**
     * Note: it is necessary to call the listeners immediately after user adds a listener using this function
     * because there is no guarantee that the newly added listener is going to be called later. <br><br>
     * we only receive variable data (and trigger handlers) on certain event calls that happens asynchronously,
     * so if client registers the listeners after such events have triggered,
     * they should still be able to get those previously updated values
     */
    public void addVariablesChangedHandler(@NonNull VariablesChangedCallback handler) {
        synchronized (variablesChangedHandlers) {
            variablesChangedHandlers.add(handler);
        }

        if (varCache.hasReceivedDiffs()) {
            handler.variablesChanged();
        }
    }

    /**
     * Note: Following the similar logic as mentioned in the comment on {@link #addVariablesChangedHandler},
     * if a user adds a OneTimeVariablesChangedHandler using this function AFTER the variable data is received,
     * (and one time handlers already triggered), the user will not have their newly
     * added handlers triggered ever. therefore, it is necessary to trigger the user's handlers
     * immediately once this function is called
     */
    public void addOneTimeVariablesChangedHandler(@NonNull VariablesChangedCallback handler) {
        if (varCache.hasReceivedDiffs()) {
            handler.variablesChanged();
        } else {
            synchronized (oneTimeVariablesChangedHandlers) {
                oneTimeVariablesChangedHandlers.add(handler);
            }
        }
    }

    public void removeVariablesChangedHandler(@NonNull VariablesChangedCallback handler) {
        synchronized (variablesChangedHandlers) {
            variablesChangedHandlers.remove(handler);
        }
    }

    public void removeOneTimeVariablesChangedHandler(
            @NonNull VariablesChangedCallback handler) { //removeOnceVariablesChangedAndNoDownloadsPendingHandler

        synchronized (oneTimeVariablesChangedHandlers) {
            oneTimeVariablesChangedHandlers.remove(handler);
        }
    }

    public void removeAllVariablesChangedHandler() {
        synchronized (variablesChangedHandlers) {
            variablesChangedHandlers.clear();
        }
    }

    public void removeAllOneTimeVariablesChangedHandler() { //removeOnceVariablesChangedAndNoDownloadsPendingHandler

        synchronized (oneTimeVariablesChangedHandlers) {
            oneTimeVariablesChangedHandlers.clear();
        }
    }

    /*   *//**
     * required by {@link VarCache#loadDiffs()} and {@link VarCache#saveDiffs()} to
     * get/store data from/to SharedPreferences.
     * @return context
     *//*
    @Nullable
    public Context getContext() {
        if (context == null) {
            Logger.v("Your application context is not set. You should call CTVariablesInternal.setApplicationContext(this) or CTActivityHelper.enableLifecycleCallbacks(this) in your application's onCreate method, or have your application extend CleverTapApplication.");
        }
        return context;
    }*/

    /*  *//**
     * sets Context
     * *//*
    public void setVariableContext(Context context) {
        this.context = context;
    }*/

    /**
     * originally <a href="https://github.com/Leanplum/Leanplum-Android-SDK/blob/master/AndroidSDKCore/src/main/java/com/leanplum/Leanplum.java#L1195">Leanplum.hasStarted()</a>
     * <br>
     * This flag indicates whether or not the SDK is still in process of receiving a response
     * from the server. <br>
     * This is used to print warnings in logs (see {@link Var#warnIfNotStarted()},
     * and prevent listeners from triggering ( see {@link Var#update()} and {@link
     * Var#addValueChangedHandler(VariableCallback)}
     * <br>
     * <br>
     *
     * @return value of {@link #variableResponseReceived  }
     */
    public Boolean isVariableResponseReceived() {
        return variableResponseReceived;
    }

    /**
     * originally <a href="https://github.com/Leanplum/Leanplum-Android-SDK/blob/master/AndroidSDKCore/src/main/java/com/leanplum/internal/LeanplumInternal.java#L705">LeanplumInternal.setHasStarted(started)</a>
     * <br><br>
     * This is set to : <br>
     * - true, when SDK receives a response for Variable data (in {@link #handleVariableResponse}) (The function is
     * always triggerred, even when api fails) <br> <br>
     *
     * <br>
     * <br>
     *
     * @param responseReceived : a boolean to be set {@link #variableResponseReceived  }
     */
    public void setVariableResponseReceived(boolean responseReceived) {
        variableResponseReceived = responseReceived; // might not be needed
    }


}

/// old docs
// * -2. CleverTapInstanceConfig calls {@link CTVariables#setContext(Context)} <br><br>
// * -1. User Calls  {@link Parser#parseVariables(Object...)} or {@link Parser#parseVariablesForClasses(Class[])} which creates a {@link Var} instance and ends up calling {@link VarCache#registerVariable(Var)} which sets : <br><br>
// *      * {@link VarCache#vars} to mapOf(varname -> Var("varname",..) ) <br>
// *      * {@link VarCache#valuesFromClient}  to mapOf("varNameG"->"varNameL"->value) (check VarCache.registerVariable()) and <br>
// *      * {@link VarCache#defaultKinds} to mapOf("varname"->varObj.kind) <br><br>
// *  0. user calls {@link CTVariables#init()}<br><br> which triggers calls to {@link VarCache#loadDiffs()} & {@link VarCache#setCacheUpdateBlock(CacheUpdateBlock)} synchronously <br>
// *  1. will set silent[g] = true in VarCache<br><br>
// *  2. will call loadDiffs() : which further calls applyVariableDiffs(cX: map<String,Object> created from cached data )
// *      which does the following:<br><br>
// * * 2.1. sets VarCache.diffs = cX<br><br>
// * * 2.2  sets VarCache.merged = mergedMap(VarCache.valuesFromClient, VarCache.diffs)<br><br>
// * * 2.3  for each var in Vache.vars,it calls var.update() which will do the following:<br><br><br><br>
// *
// * * 2.3.1 set var.value = getMergedValueFromComponentArray() : which is basically the value from VarCache.merged or VarCache.valuesFromClient(if merged is null)<br><br>
// * * 2.3.2 update var's other private variables<br><br>
// * * 2.3.3 optionally trigger var's individual callbacks if CTVariablesInternal.hasStarted() is true
// *
// * * 2.4 if silent is false(currently its true, so skip for now) <br><br>
// * * * 2.4.1 call safeDiffs() : which saves Varcache.diffs (from 2.1) to cache<br><br>
// * * * 2.4.2 call :triggerHasReceivedDiffs() : triggers users callbacks (which gets set in step 4 later)<br><br>
// *
// *  3.  sets silent[g] = false<br><br>
// *  4. sets global listeners used in 2.4.2 via VarCache.setCacheUpdateBlock(..)<br><br>
// *  5. call : requestVariableDataFromServer() which creates a seperate thread, it requests for data and calls handleStartResponse(resp data):<br><br>
// *  5.1a.1 will call setHasStarted(true)<br><br>
// *  5.1a.2 if data is not available, it will call loadDiffs, which would again do step 2(2.1-2.4) with silent=false . so it will be doing 2.4.1 and 2.4.2 (i.e call saveDiffs() and triggerHasReceivedDiffs()) <br><br>
// *
// * 5.1b.1 gets variant Values (server has 2 values : 'variants' and 'locals' (my names) . <br>
// *       locals are values received for keys from code(keys being the variable names, again from the code)BY the server.<br><br>
// *       the variant are changed values for the same keys that were update  ON the Server)<br><br>
// * 5.2b.2 calls VarCache.applyVariableDiffs(variants)->computeMergedDictionary. which means doing step 2(2.1-2.4) also since silent is still false, therefore also do 2.4.1, 2.4.2 <br><br>
// * 5.2b.3 if development mode is enabled, then also call VarCache.setDevModeValuesFromServer(locals) with locals which does step 6.1-6.4 <br><br>
// */