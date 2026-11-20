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

These are the only required changes outside the copied module folder. The `app/src/test/java/net/gsantner/markor/ai_assistant/` tests in this repository are optional and are not needed at runtime.
