#!/bin/sh
# Stands in for Claude Code in tests: answers like `claude -p --output-format stream-json --verbose`, by role, and
# makes a real edit as the coder. Notes what it was given in /tmp/fake-claude.log inside the sandbox.
log=/tmp/fake-claude.log
echo "args: $*" >> "$log"
echo "env: token=${CLAUDE_CODE_OAUTH_TOKEN:+set} telemetry=$DISABLE_TELEMETRY nonessential=$CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC scrub=$CLAUDE_CODE_SUBPROCESS_ENV_SCRUB config=$CLAUDE_CONFIG_DIR" >> "$log"
tools=
model=default
system=
while [ $# -gt 0 ]; do
	case "$1" in
		--tools) tools="$2"; shift ;;
		--model) model="$2"; shift ;;
		--append-system-prompt-file) system="$2"; shift ;;
	esac
	shift
done
brief=$(cat)
[ -n "$brief" ] && echo "brief: read $(printf '%s' "$brief" | wc -c) bytes" >> "$log"

init() {
	printf '{"type":"system","subtype":"init","cwd":"%s","session_id":"fake","model":"%s","tools":[],"permissionMode":"dontAsk","claude_code_version":"0.0.0-fake"}\n' "$PWD" "$model"
}
say() {
	printf '{"type":"assistant","message":{"id":"%s","type":"message","role":"assistant","model":"%s","content":[{"type":"text","text":"%s"}],"usage":{"input_tokens":100,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":1}},"parent_tool_use_id":null,"session_id":"fake"}\n' "$1" "$model" "$2"
}
use() {
	printf '{"type":"assistant","message":{"id":"%s","type":"message","role":"assistant","model":"%s","content":[{"type":"tool_use","id":"%s","name":"%s","input":%s}],"usage":{"input_tokens":200,"cache_creation_input_tokens":0,"cache_read_input_tokens":1000,"output_tokens":1}},"parent_tool_use_id":null,"session_id":"fake"}\n' "$1" "$model" "$2" "$3" "$4"
}
result() {
	printf '{"type":"user","message":{"role":"user","content":[{"tool_use_id":"%s","type":"tool_result","content":"%s"}]},"parent_tool_use_id":null,"session_id":"fake"}\n' "$1" "$2"
}
finish() {
	printf '{"type":"result","subtype":"success","is_error":false,"duration_ms":10,"num_turns":%s,"result":"%s","session_id":"fake","total_cost_usd":%s,"usage":{"input_tokens":500,"cache_creation_input_tokens":0,"cache_read_input_tokens":2000,"output_tokens":80}}\n' "$1" "$2" "$3"
}

init
case "$tools" in
	"")
		say m1 'RISK: LOW\nRATIONALE: copy change'
		finish 1 'RISK: LOW\nRATIONALE: copy change' 0.01 ;;
	*Bash*)
		use m1 t1 Read '{"file_path":"/workspace/hello.txt"}'
		result t1 "$(head -1 hello.txt)"
		sed -i 's/hello world/hello agents/' hello.txt
		use m2 t2 Edit '{"file_path":"/workspace/hello.txt","old_string":"hello world","new_string":"hello agents"}'
		result t2 "edited with token $CLAUDE_CODE_OAUTH_TOKEN"
		say m3 'Changed hello.txt to greet agents.'
		finish 3 'Changed hello.txt to greet agents.' 0.05 ;;
	*)
		grep -q 'show_diff is the file /tmp/.agentic-cli/' "$system" && echo "system: has the engine note" >> "$log"
		if grep -q 'VERDICT: APPROVE' "$system"; then
			use m1 t1 Read '{"file_path":"/workspace/hello.txt"}'
			result t1 "$(head -1 hello.txt)"
			say m2 'The change matches the request.'
			finish 2 'Fine.\nVERDICT: APPROVE' 0.01
		else
			say m1 'Writing the specification.'
			finish 1 '## Requirements\n1. WHEN the page is read THE SYSTEM SHALL greet agents.' 0.01
		fi ;;
esac
