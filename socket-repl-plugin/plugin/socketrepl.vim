let s:p_dir = expand('<sfile>:p:h')
let g:is_running = 0
let g:channel = -1

function! StartIfNotRunning()
    if g:is_running == 0
        echo 'starting plugin...'
        "TODO - This is a dirty hack. We should launch things without changing
        "the working directory.
        exec ':cd ' . s:p_dir
        exec ':cd ..'
        let g:channel = rpcstart('java', ['-jar', 'socket-repl-plugin-0.1.0-SNAPSHOT-standalone.jar'])
        let g:is_running = 1
    endif
endfunction

function! Connect()
    call StartIfNotRunning()
    let res = rpcrequest(g:channel, 'connect', [])
    return res
endfunction
command! Connect call Connect()

function! EvalBuffer()
    call StartIfNotRunning()
    let res = rpcrequest(g:channel, 'eval-buffer', [])
    return res
endfunction
command! EvalBuffer call EvalBuffer()

function! EvalCode()
    call StartIfNotRunning()
    let res = rpcrequest(g:channel, 'eval-code', [])
    return res
endfunction
command! EvalCode call EvalCode()

function! ReplLog()
    call StartIfNotRunning()
    let res = rpcrequest(g:channel, 'show-log', [])
    return res
endfunction
command! ReplLog call ReplLog()

echo 'prod socket repl plugin loaded!'
