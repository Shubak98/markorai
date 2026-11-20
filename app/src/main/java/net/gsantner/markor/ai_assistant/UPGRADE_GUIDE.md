# AI Assistant portability

## Copy the module

Copy the entire `app/src/main/java/net/gsantner/markor/ai_assistant/` folder into the same path in the new Markor repository. No resource, Gradle, or settings-file changes are required.

## Paste the hook

In the new `DocumentEditAndViewFragment.java`, add the field to the fragment class and paste the initialization inside `onCreate`, directly after `super.onCreate(savedInstanceState)`. This registers the voice result launcher before the fragment is started:

```java
private net.gsantner.markor.ai_assistant.AiAssistantModule aiAssistantModule;

@Override
public void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // >>> AI_ASSISTANT_HOOK_START
    aiAssistantModule = new net.gsantner.markor.ai_assistant.AiAssistantModule(this);
    // <<< AI_ASSISTANT_HOOK_END
    // Keep the new version's existing onCreate implementation here.
}
```

In `onCreateOptionsMenu`, add the following after the editor menu is created. Keep the existing `isText` variable or replace it with an equivalent text-document check:

```java
// >>> AI_ASSISTANT_HOOK_START
final MenuItem aiEditItem = menu.add(Menu.NONE, Menu.NONE, Menu.NONE, "AI Edit");
aiEditItem.setVisible(isText && !_isPreviewVisible);
aiEditItem.setOnMenuItemClickListener(item -> {
    aiAssistantModule.openEditorDialog(_hlEditor);
    return true;
});
// <<< AI_ASSISTANT_HOOK_END
```

In `onViewCreated`, after the initial call that selects edit or preview mode and after `_hlEditor` has been assigned, attach the floating button and provide the save/preview callback:

```java
// >>> AI_ASSISTANT_HOOK_START
if (!_document.isBinaryFileNoTextLoading()) {
    aiAssistantModule.attachFloatingButton(view, _hlEditor, () -> {
        if (saveDocument(false) && _isPreviewVisible) {
            updateViewModeText();
        }
    });
}
aiAssistantModule.setViewModeVisible(_isPreviewVisible);
// <<< AI_ASSISTANT_HOOK_END
```

In the central `setViewModeVisibility(boolean show, boolean animate)` method, update the floating button immediately after `_isPreviewVisible` is assigned. This keeps it visible in preview mode and hidden in edit mode:

```java
_isPreviewVisible = show;
// >>> AI_ASSISTANT_HOOK_START
aiAssistantModule.setViewModeVisible(show);
// <<< AI_ASSISTANT_HOOK_END
```

The floating button and the menu item both open the same AI Edit dialog. Applying a diff updates `_hlEditor`, saves the document, and refreshes the WebView preview when view mode is active. The `app/src/test/java/net/gsantner/markor/ai_assistant/` tests in this repository are optional and are not needed at runtime.
