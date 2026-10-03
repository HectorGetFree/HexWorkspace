<template>
  <section class="hexgent-page">
    <AgentConversation
      v-if="workspaceId"
      :conversation-id="conversationId"
      :workspace-id="workspaceId"
      :workspace-name="workspaceName"
      variant="page"
      @conversation-created="openConversation"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as workspaceApi from '../api/workspace'
import AgentConversation from '../features/agent/AgentConversation.vue'
import { useAgentConversationStore } from '../stores/agentConversation'

const route = useRoute()
const router = useRouter()
const agentConversation = useAgentConversationStore()
const workspaceId = ref<number | null>(null)
const workspaceName = ref('')
const conversationId = computed(() => String(route.params.conversationId ?? '') || null)

watch(conversationId, (value) => {
  agentConversation.selectConversation(value)
  if (value) void agentConversation.hydrateConversation(value, workspaceName.value)
}, { immediate: true })

onMounted(async () => {
  const workspaces = await workspaceApi.listMyWorkspaces()
  const workspace = workspaces.find((item) => item.isDefault) ?? workspaces[0]
  workspaceId.value = workspace?.workspaceId ?? null
  workspaceName.value = workspace?.workspaceName ?? ''
})

function openConversation(id: string): void {
  if (conversationId.value === id) return
  void router.replace(`/hexgent/conversations/${id}`)
}
</script>
