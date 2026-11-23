#!/usr/bin/env bash
set -euo pipefail

data_dir=../data

mask_secrets() {
    local name value address
    local -a addresses
    for name in ANTHROPIC_API_KEY OPENAI_API_KEY GITHUB_TOKEN SMTP_USERNAME SMTP_PASSWORD MAIL_TO; do
        value=${!name:-}
        [[ -n $value ]] || continue
        value=${value//%/%25}
        value=${value//$'\r'/%0D}
        value=${value//$'\n'/%0A}
        printf '::add-mask::%s\n' "$value"
    done
    IFS=, read -r -a addresses <<< "${MAIL_TO:-}"
    for address in "${addresses[@]}"; do
        address="${address#"${address%%[![:space:]]*}"}"
        address="${address%"${address##*[![:space:]]}"}"
        [[ -n $address ]] || continue
        address=${address//%/%25}
        address=${address//$'\r'/%0D}
        address=${address//$'\n'/%0A}
        printf '::add-mask::%s\n' "$address"
    done
}

data_git() {
    local -a limit=()
    case "$1" in
        fetch|rebase) limit=(timeout --kill-after=5s 1m) ;;
        push) limit=(timeout --kill-after=5s "${COLLECTOR_PUSH_TIMEOUT:-3m}") ;;
    esac
    "${limit[@]}" git -C "$data_dir" -c user.name='github-actions[bot]' \
        -c user.email='41898282+github-actions[bot]@users.noreply.github.com' "$@"
}

check_data_tree() {
    [[ -d $data_dir && -z $(find "$data_dir" -type l -print -quit) ]]
}

collector_run() {
    if [[ -n ${COLLECTOR_PUBLICATION_JAVA:-} ]]; then
        timeout --kill-after=5s 2m "$COLLECTOR_PUBLICATION_JAVA" -cp "$COLLECTOR_PUBLICATION_CLASSPATH" collector.dev.MainKt "$@"
    else
        timeout --kill-after=5s 5m bash gradlew :core:run --args="$*"
    fi
}

resolve_rebase() {
    local path side temp
    local -a conflicts
    mapfile -d '' -t conflicts < <(data_git diff --name-only --diff-filter=U -z)
    [[ ${#conflicts[@]} -gt 0 ]] || return 1
    for path in "${conflicts[@]}"; do
        # Git may move a file aside when the other side uses its name as a directory.
        [[ $path != *~* && ! -d $data_dir/$path ]] || return 1
        case "$path" in
            inbox/*) side=ours ;;
            out/mail/sent.json)
                temp=$(mktemp -d)
                data_git show ":2:$path" > "$temp/remote" 2>/dev/null || printf '[]' > "$temp/remote"
                data_git show ":3:$path" > "$temp/local" 2>/dev/null || printf '[]' > "$temp/local"
                collector_run --data-merge-mail "$temp/remote" "$temp/local" "$temp/merged" || return 1
                mkdir -p "$data_dir/out/mail" || return 1
                mv -- "$temp/merged" "$data_dir/$path" || return 1
                rm -- "$temp/remote" "$temp/local"
                rmdir -- "$temp"
                data_git add -- "$path" || return 1
                continue
                ;;
            *) side=theirs ;;
        esac
        if data_git checkout "--$side" -- "$path"; then
            data_git add -- "$path" || return 1
        else
            data_git rm -f -- "$path" || return 1
        fi
    done
    GIT_EDITOR=true data_git rebase --continue
}

build_publication() {
    local path
    check_data_tree || return 1
    collector_run --data-push "$data_dir" "${options[@]}" || return 1
    data_git add -- manifest.json || return 1
    for path in "${paths[@]}"; do
        if [[ -e $data_dir/$path || -n $(data_git ls-files -- "$path") ]]; then
            data_git add --all -- "$path" || return 1
        fi
    done
    if [[ $mode != publish-mail ]]; then
        for path in "${removed_inbox[@]}"; do
            if [[ ! -e $data_dir/$path ]]; then
                data_git rm --cached --ignore-unmatch -- "$path" || return 1
            fi
        done
    fi
    if ! data_git diff --cached --quiet; then
        data_git commit -qm "data: $(date +%F) $label" || return 1
    fi
}

case "${1:-publish-state}" in
    checkout)
        if timeout --kill-after=5s 1m git ls-remote --exit-code --heads origin refs/heads/data >/dev/null; then
            timeout --kill-after=5s 1m git fetch --no-tags origin refs/heads/data
            git worktree add --detach "$data_dir" FETCH_HEAD
        else
            status=$?
            [[ $status == 2 ]] || exit "$status"
            git worktree add --detach "$data_dir" HEAD
            git -C "$data_dir" checkout --orphan collector-data
            git -C "$data_dir" rm -rf -- .
        fi
        # The data branch is writable by the app. Reject symlinks before using it.
        check_data_tree
        ;;
    daily)
        mask_secrets
        chmod +x scripts/actions-data.sh
        export COLLECTOR_STATE_HOOK="$PWD/scripts/actions-data.sh"
        timeout --kill-after=5s 150m bash gradlew :core:run --args="--scheduled --no-mail"
        ;;
    mail)
        mask_secrets
        timeout --kill-after=5s 150m bash gradlew :core:run --args="--mail-scheduled --publish"
        ;;
    analyze)
        mask_secrets
        timeout --kill-after=5s 150m bash gradlew :core:run --args=--data-analyze
        ;;
    analysis-mail)
        mask_secrets
        timeout --kill-after=5s 150m bash gradlew :core:run --args=--data-analysis-mail
        ;;
    publish|publish-mail|publish-state)
        mode=${1:-publish-state}
        label=${2:-batch-checkpoint}
        options=()
        removed_inbox=()
        lock_options=()
        if [[ ${3:-} == --under-run-lock || $mode == publish-state ]]; then
            lock_options+=(--under-run-lock)
        fi
        options+=("${lock_options[@]}")
        if [[ $mode == publish-mail ]]; then
            options+=(--mail-only)
            paths=(out/mail/sent.json)
        elif [[ $mode == publish-state ]]; then
            options+=(--state-only)
            paths=(out/batch/public out/cost-ledger out/cost-ledger-analysis)
        else
            paths=(out config/prompts settings)
        fi
        if [[ $mode != publish-mail ]]; then
            mapfile -d '' -t removed_inbox < <(data_git ls-files --deleted -z -- inbox)
            inbox_base=$(data_git rev-parse --verify HEAD 2>/dev/null || true)
        fi
        build_publication
        for ((attempt=1; attempt<=3; attempt++)); do
            if data_git push origin HEAD:refs/heads/data; then
                exit 0
            fi
            [[ $attempt != 3 ]] || exit 1
            if ! data_git fetch --no-tags origin +refs/heads/data:refs/remotes/origin/data; then
                continue
            fi
            data_git ls-tree -r origin/data | awk '$1 == "120000" { found = 1 } END { exit found }'
            if ! data_git rebase --autostash --keep-empty --reapply-cherry-picks --empty=keep origin/data; then
                if ! resolve_rebase; then
                    data_git rebase --abort || { data_git rebase --quit || true; }
                    data_git reset --merge origin/data
                    for path in "${removed_inbox[@]}"; do
                        original=$(data_git rev-parse --verify "$inbox_base:$path" 2>/dev/null) || continue
                        current=$(data_git rev-parse --verify "HEAD:$path" 2>/dev/null) || continue
                        if [[ $original == "$current" ]]; then
                            data_git rm -- "$path"
                        fi
                    done
                    build_publication
                    continue
                fi
            fi
            if [[ $mode == publish ]]; then
                build_publication
                continue
            fi
            check_data_tree
            collector_run --data-manifest "$data_dir" "${lock_options[@]}"
            data_git add -- manifest.json
            if [[ $(data_git rev-parse HEAD) == "$(data_git rev-parse origin/data)" ]]; then
                data_git commit -qm "data: $(date +%F) $label manifest"
            else
                data_git commit --amend --no-edit --allow-empty
            fi
        done
        ;;
    *)
        exit 2
        ;;
esac
