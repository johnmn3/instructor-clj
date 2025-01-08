(ns instructor-clj.core
  (:require [cheshire.core :as cc]
            [org.httpkit.client :as http]
            [malli.core :as m]
            [malli.json-schema :as json-schema]
            [stencil.core :as sc])
  (:import [com.fasterxml.jackson.core JsonParseException]))

(def ^:const default-client-params {:max-tokens 4096
                                    :temperature 0.7
                                    :model "gpt-3.5-turbo"})


(defn schema->system-prompt
  "Converts a malli schema into JSON schema and generates a system prompt for responses"
  [schema]
  (sc/render-string
   "As a genius expert, your task is to understand the content and provide
    the parsed objects in json that match the following json_schema:
    \n\n
    {{schema}}
    \n\n
    Make sure to return an instance of only the JSON.
    Refrain from returning the schema or any text explaining the JSON"
   {:schema (json-schema/transform schema)}))


(defn call-llm-api
  "Makes a POST request to a specified LLM API endpoint with given headers and body."
  [api-url headers body]
  (try
    (-> (http/post api-url {:headers headers
                            :body body})
        deref ;; Dereference the future
        :body
        (cc/parse-string true))
    (catch JsonParseException _)))


(defn parse-generated-body
  "Parses the body of a response generated by an LLM API call.
   Extracts and converts the message content into a Clojure map."
  [content]
  (try
    (cc/parse-string content true)
    (catch JsonParseException _
          ;; Handle LLM returning JSON wrapped in markdown
      (when (re-find #"^```json" content)
        (-> content
            (clojure.string/replace #"^```json" "")
            (clojure.string/replace #"```$" "")
            clojure.string/trim
            (cc/parse-string true))))))

(def default-api-url "https://api.openai.com/v1/chat/completions")

(defn llm->response
  "The function performs the LLM call and tries to destructure and get the actual response.
   Returns nil in cases where the LLM is not able to generate the expected response.

  @TODO Add ability to plugin different LLMs
  @TODO Getting response is brittle and not extensible for different LLMs"
  [{:keys [api-url custom-opts prompt response-schema api-key max-tokens model temperature]}]
  (let [api-url (or api-url default-api-url)
        using-default? (= default-api-url api-url)
        headers {"Authorization" (str "Bearer " api-key)
                 "Content-Type" "application/json"}
        llm-body (cc/generate-string
                  (merge
                   {"model" model}
                   (if using-default?
                     {"messages"  [{"role" "system"
                                    "content" (schema->system-prompt response-schema)}
                                   {"role" "user"
                                    "content" prompt}]
                      "temperature" temperature
                      "max_tokens" max-tokens}
                     (merge
                      {"stream" false
                       "prompt" prompt}
                      (when response-schema
                        {"system" (schema->system-prompt response-schema)})
                      (when max-tokens
                        {"eval_count" max-tokens})
                      (when temperature
                        {"options" {"temperature" temperature}})
                      custom-opts))))
        body (call-llm-api api-url headers llm-body)
        response (parse-generated-body
                  (if using-default?
                    (-> body :choices first :message :content)
                    (:response body)))]
    (when (m/validate response-schema response)
      response)))

(defn instruct
  "Attempts to obtain a valid response from the LLM based on the given prompt and schema,
   retrying up to `max-retries` times if necessary."
  [prompt response-schema
   & {:keys [api-key _max-tokens _model _temperature max-retries] :as client-params
      :or {max-retries 0}}]
  (loop [retries-left max-retries]
    (let [params (merge default-client-params
                        client-params
                        (when api-key {:api-key api-key})
                        {:prompt prompt
                         :response-schema response-schema})
          response (llm->response params)]
      (if (and (nil? response)
               (pos? retries-left))
        (recur (dec retries-left))
        response))))

(comment

  ;; ollama configuration

  (def model "qwen2.5-coder:1.5b")
  (def api-url "http://localhost:11434/api/generate")
  (instruct "hi there" [:map] :model model :api-key api-key :api-url api-url :max-retries 3)

  ,)

(defn create-chat-completion
  "Creates a chat completion using OpenAI API.

   This function takes OpenAI chat completion function as the first argument.

   Second argument is a map with keys :messages, :model, and :response-model.
   :messages should be a vector of maps, each map representing a message with keys :role and :content.
   :model specifies the OpenAI model to use for generating completions.
   :response-model is a map specifying the schema and name of the response model.

   Alternatively the api-key, organization, api-endpoint can be passed in
   the options argument of each api function.
   https://github.com/wkok/openai-clojure/blob/main/doc/01-usage-openai.md#options

   Also, request options may be set on the underlying hato http client by adding
   a :request map to :options for example setting the request timeout.
   https://github.com/wkok/openai-clojure/blob/main/doc/01-usage-openai.md#request-options

   Example:
   (require '[instructor-clj.core :as ic])
   (require '[wkok.openai-clojure.api :as client])

   (def User
     [:map
       [:name :string]
       [:age :int]])

   (ic/create-chat-completion
    client
    {:messages [{:role \"user\", :content \"Jason Liu is 30 years old\"}]
     :model \"gpt-3.5-turbo\"
     :response-model User})

   Returns a map with extracted information in a structured format."
  ([chat-completion-fn client-params]
   (create-chat-completion chat-completion-fn client-params nil))
  ([chat-completion-fn client-params opts]
   (let [response-model (:response-model client-params)
         messages (apply conj
                         [{:role "system" :content (schema->system-prompt response-model)}]
                         (:messages client-params))
         client-params (-> default-client-params
                           (merge client-params
                                  {:messages messages})
                           (dissoc :response-model))
         body (chat-completion-fn client-params opts)
         response (parse-generated-body body)]
     (if (m/validate response-model response)
       response
       body))))


;; Example usage
(comment

  (def api-key "<API-KEY>")
  ;; https://github.com/jxnl/instructor/blob/cea534fd2280371d2778e0f043d3fe557cc7bc7e/instructor/process_response.py#L245C17-L250C83

  (def User
    [:map
     [:name :string]
     [:age :int]])

  (instruct "John Doe is 30 years old."
            User
            :api-key api-key
            :max-retries 0)

  (def Meeting
    [:map
     [:action [:and {:description "What action is needed"}
               [:enum "call" "followup"]]]
     [:person [:and {:description "Person involved in the action"}
               [:string]]]
     [:time [:and {:description "Time of the day"}
             [:string]]]
     [:day [:and {:description "Day of the week"}
            [:string]]]])

  (= (instruct "Call Kapil on Saturday at 12pm"
               Meeting
               :api-key api-key
               :model "gpt-4"
               :max-retries 2)
     {:action "call", :person "Kapil", :time "12pm", :day "Saturday"})

  (require '[wkok.openai-clojure.api :as client])
  (create-chat-completion client/create-chat-completion
                          {:messages [{:role "user" :content "Call Kapil on Saturday at 12pm"}]
                           :response-model Meeting}
                          {:api-key api-key})
  )
