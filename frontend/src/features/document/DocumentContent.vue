<template>
  <div class="editor-shell">
    <div class="editor-toolbar">
      <button type="button" :disabled="!editor" @click="editor?.chain().focus().undo().run()">↶</button>
      <button type="button" :disabled="!editor" @click="editor?.chain().focus().redo().run()">↷</button>
      <button type="button" :class="{ active: editor?.isActive('bold') }" :disabled="!editor" @click="editor?.chain().focus().toggleBold().run()"><strong>B</strong></button>
      <button type="button" :class="{ active: editor?.isActive('italic') }" :disabled="!editor" @click="editor?.chain().focus().toggleItalic().run()"><em>I</em></button>
      <button type="button" :class="{ active: editor?.isActive('heading', { level: 2 }) }" :disabled="!editor" @click="editor?.chain().focus().toggleHeading({ level: 2 }).run()">H2</button>
      <button type="button" :class="{ active: editor?.isActive('bulletList') }" :disabled="!editor" @click="editor?.chain().focus().toggleBulletList().run()">• 列表</button>
      <button type="button" :class="{ active: editor?.isActive('orderedList') }" :disabled="!editor" @click="editor?.chain().focus().toggleOrderedList().run()">1. 列表</button>
    </div>
    <EditorContent :editor="editor" class="tiptap-content" />
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'
import { EditorContent, useEditor } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import { htmlToMarkdown, markdownToHtml } from './markdown'

const props = defineProps<{ content: string; editable: boolean }>()
const emit = defineEmits<{ change: [markdown: string] }>()
function handleEditorUpdate({ editor: instance }: { editor: { getHTML: () => string } }): void {
  emit('change', htmlToMarkdown(instance.getHTML()))
}
const editor = useEditor({
  extensions: [StarterKit], content: markdownToHtml(props.content), editable: props.editable,
})
watch(editor, (instance, previous) => {
  previous?.off('update', handleEditorUpdate)
  instance?.on('update', handleEditorUpdate)
}, { immediate: true })
watch(() => props.content, (content) => { if (editor.value && htmlToMarkdown(editor.value.getHTML()) !== content) editor.value.commands.setContent(markdownToHtml(content), { emitUpdate: false }) })
watch(() => props.editable, (editable) => editor.value?.setEditable(editable))
onBeforeUnmount(() => {
  editor.value?.off('update', handleEditorUpdate)
  editor.value?.destroy()
})
</script>
