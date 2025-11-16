package io.github.dorumrr.de1984.ui.common

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Standard reusable dialog component for simple dialogs across the app.
 * 
 * Use this for:
 * - Confirmation dialogs (two buttons)
 * - Error messages (one button)
 * - Information messages (one button)
 * - Welcome/onboarding dialogs (two buttons)
 * 
 * For complex permission setup flows with status badges and cards,
 * use PermissionSetupDialog instead.
 * 
 * This dialog follows Material Design 3 standards and matches the styling
 * of the firewall start dialog in MainActivity.
 */
object StandardDialog {

    /**
     * Show a standard dialog with customizable title, message, and buttons.
     * 
     * @param context The context to show the dialog in
     * @param title Dialog title
     * @param message Dialog message/description
     * @param positiveButtonText Text for the positive (confirm) button
     * @param onPositiveClick Callback when positive button is clicked
     * @param negativeButtonText Optional text for the negative (cancel) button. If null, no negative button is shown.
     * @param onNegativeClick Optional callback when negative button is clicked
     * @param cancelable Whether the dialog can be dismissed by tapping outside or pressing back (default: true)
     * @param onDismiss Optional callback when dialog is dismissed
     */
    fun show(
        context: Context,
        title: String,
        message: String,
        positiveButtonText: String,
        onPositiveClick: () -> Unit = {},
        negativeButtonText: String? = null,
        onNegativeClick: (() -> Unit)? = null,
        cancelable: Boolean = true,
        onDismiss: (() -> Unit)? = null
    ) {
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveButtonText) { _, _ -> onPositiveClick() }
            .setCancelable(cancelable)

        // Add negative button if text is provided
        if (negativeButtonText != null) {
            builder.setNegativeButton(negativeButtonText) { _, _ -> 
                onNegativeClick?.invoke()
            }
        }

        // Add dismiss listener if provided
        if (onDismiss != null) {
            builder.setOnDismissListener { onDismiss() }
        }

        builder.show()
    }

    /**
     * Convenience method for showing an error dialog with a single "OK" button.
     * 
     * @param context The context to show the dialog in
     * @param message Error message to display
     * @param title Dialog title (default: "Error")
     * @param onDismiss Optional callback when dialog is dismissed
     */
    fun showError(
        context: Context,
        message: String,
        title: String = context.getString(io.github.dorumrr.de1984.R.string.dialog_error_title),
        onDismiss: (() -> Unit)? = null
    ) {
        show(
            context = context,
            title = title,
            message = message,
            positiveButtonText = context.getString(io.github.dorumrr.de1984.R.string.dialog_ok),
            onPositiveClick = { onDismiss?.invoke() },
            cancelable = true,
            onDismiss = onDismiss
        )
    }

    /**
     * Convenience method for showing an information dialog with a single "OK" button.
     * 
     * @param context The context to show the dialog in
     * @param title Dialog title
     * @param message Information message to display
     * @param onDismiss Optional callback when dialog is dismissed
     */
    fun showInfo(
        context: Context,
        title: String,
        message: String,
        onDismiss: (() -> Unit)? = null
    ) {
        show(
            context = context,
            title = title,
            message = message,
            positiveButtonText = context.getString(io.github.dorumrr.de1984.R.string.dialog_ok),
            onPositiveClick = { onDismiss?.invoke() },
            cancelable = true,
            onDismiss = onDismiss
        )
    }

    /**
     * Convenience method for showing a confirmation dialog with "Confirm" and "Cancel" buttons.
     * 
     * @param context The context to show the dialog in
     * @param title Dialog title
     * @param message Confirmation message to display
     * @param confirmButtonText Text for the confirm button (default: "Confirm")
     * @param onConfirm Callback when confirm button is clicked
     * @param cancelButtonText Text for the cancel button (default: "Cancel")
     * @param onCancel Optional callback when cancel button is clicked
     */
    fun showConfirmation(
        context: Context,
        title: String,
        message: String,
        confirmButtonText: String = "Confirm",
        onConfirm: () -> Unit,
        cancelButtonText: String = "Cancel",
        onCancel: (() -> Unit)? = null
    ) {
        show(
            context = context,
            title = title,
            message = message,
            positiveButtonText = confirmButtonText,
            onPositiveClick = onConfirm,
            negativeButtonText = cancelButtonText,
            onNegativeClick = onCancel,
            cancelable = true
        )
    }

    /**
     * Convenience method for showing a "no privileged access" dialog.
     * This replaces the legacy PrivilegedAccessDialog.showRequiredDialog().
     * 
     * @param context The context to show the dialog in
     * @param onDismiss Optional callback when dialog is dismissed
     */
    fun showNoAccessDialog(
        context: Context,
        onDismiss: (() -> Unit)? = null
    ) {
        show(
            context = context,
            title = context.getString(io.github.dorumrr.de1984.R.string.dialog_privileged_access_title),
            message = context.getString(io.github.dorumrr.de1984.R.string.dialog_no_access_message),
            positiveButtonText = context.getString(io.github.dorumrr.de1984.R.string.dialog_ok),
            onPositiveClick = { onDismiss?.invoke() },
            cancelable = true,
            onDismiss = onDismiss
        )
    }

    /**
     * Convenience method for showing a "root access denied" dialog.
     * This replaces the legacy PrivilegedAccessDialog.showRootDeniedDialog().
     * 
     * @param context The context to show the dialog in
     * @param onOkClick Optional callback when OK button is clicked
     */
    fun showRootDeniedDialog(
        context: Context,
        onOkClick: (() -> Unit)? = null
    ) {
        show(
            context = context,
            title = context.getString(io.github.dorumrr.de1984.R.string.dialog_privileged_access_title),
            message = context.getString(io.github.dorumrr.de1984.R.string.dialog_root_denied_message),
            positiveButtonText = context.getString(io.github.dorumrr.de1984.R.string.dialog_ok),
            onPositiveClick = { onOkClick?.invoke() },
            cancelable = true,
            onDismiss = onOkClick
        )
    }

    /**
     * Show a type-to-confirm dialog that requires the user to type a specific word to confirm.
     * Used for critical/dangerous operations.
     *
     * @param context The context to show the dialog in
     * @param title The dialog title
     * @param message The dialog message
     * @param confirmWord The word the user must type to confirm (default: "UNINSTALL")
     * @param confirmButtonText Text for the confirm button (default: "Confirm")
     * @param onConfirm Callback when user types the correct word and clicks confirm
     * @param onCancel Optional callback when cancel button is clicked
     */
    fun showTypeToConfirm(
        context: Context,
        title: String,
        message: String,
        confirmWord: String = "UNINSTALL",
        confirmButtonText: String = "Confirm",
        onConfirm: () -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        // Create EditText for user input
        val editText = android.widget.EditText(context).apply {
            hint = context.getString(io.github.dorumrr.de1984.R.string.dialog_type_to_confirm_hint, confirmWord)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                       android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(40, 20, 40, 20)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setView(editText)
            .setPositiveButton(confirmButtonText, null) // Set to null initially
            .setNegativeButton(context.getString(io.github.dorumrr.de1984.R.string.dialog_cancel)) { _, _ ->
                onCancel?.invoke()
            }
            .setCancelable(true)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            positiveButton.isEnabled = false // Disable initially

            // Enable/disable button based on input
            editText.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    positiveButton.isEnabled = s?.toString()?.equals(confirmWord, ignoreCase = true) == true
                }
            })

            positiveButton.setOnClickListener {
                if (editText.text.toString().equals(confirmWord, ignoreCase = true)) {
                    dialog.dismiss()
                    onConfirm()
                }
            }
        }

        dialog.show()
    }
}

